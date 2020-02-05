/*
 * Copyright 2019 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.ta.reportportal.core.item.impl;

import com.epam.ta.reportportal.commons.ReportPortalUser;
import com.epam.ta.reportportal.commons.validation.Suppliers;
import com.epam.ta.reportportal.core.analyzer.auto.LogIndexer;
import com.epam.ta.reportportal.core.events.attachment.DeleteTestItemAttachmentsEvent;
import com.epam.ta.reportportal.core.item.DeleteTestItemHandler;
import com.epam.ta.reportportal.dao.LaunchRepository;
import com.epam.ta.reportportal.dao.LogRepository;
import com.epam.ta.reportportal.dao.TestItemRepository;
import com.epam.ta.reportportal.entity.enums.StatusEnum;
import com.epam.ta.reportportal.entity.item.TestItem;
import com.epam.ta.reportportal.entity.launch.Launch;
import com.epam.ta.reportportal.entity.project.ProjectRole;
import com.epam.ta.reportportal.entity.user.UserRole;
import com.epam.ta.reportportal.exception.ReportPortalException;
import com.epam.ta.reportportal.ws.model.ErrorType;
import com.epam.ta.reportportal.ws.model.OperationCompletionRS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.epam.ta.reportportal.commons.Predicates.equalTo;
import static com.epam.ta.reportportal.commons.Predicates.not;
import static com.epam.ta.reportportal.commons.validation.BusinessRule.expect;
import static com.epam.ta.reportportal.commons.validation.Suppliers.formattedSupplier;
import static com.epam.ta.reportportal.ws.model.ErrorType.*;
import static java.util.Optional.ofNullable;

/**
 * Default implementation of {@link DeleteTestItemHandler}
 *
 * @author Andrei Varabyeu
 * @author Andrei_Ramanchuk
 */
@Service
public class DeleteTestItemHandlerImpl implements DeleteTestItemHandler {

	private final TestItemRepository testItemRepository;

	private final LogRepository logRepository;

	private final LogIndexer logIndexer;

	private final LaunchRepository launchRepository;

	private final ApplicationEventPublisher eventPublisher;

	@Autowired
	public DeleteTestItemHandlerImpl(TestItemRepository testItemRepository, LogRepository logRepository, LogIndexer logIndexer,
			LaunchRepository launchRepository, ApplicationEventPublisher eventPublisher) {
		this.testItemRepository = testItemRepository;
		this.logRepository = logRepository;
		this.logIndexer = logIndexer;
		this.launchRepository = launchRepository;
		this.eventPublisher = eventPublisher;
	}

	@Override
	public OperationCompletionRS deleteTestItem(Long itemId, ReportPortalUser.ProjectDetails projectDetails, ReportPortalUser user) {
		TestItem item = testItemRepository.findById(itemId)
				.orElseThrow(() -> new ReportPortalException(ErrorType.TEST_ITEM_NOT_FOUND, itemId));
		Launch launch = launchRepository.findById(item.getLaunchId())
				.orElseThrow(() -> new ReportPortalException(ErrorType.LAUNCH_NOT_FOUND, item.getLaunchId()));

		validate(item, launch, user, projectDetails);
		Optional<TestItem> parent = ofNullable(item.getParent());

		testItemRepository.deleteById(item.getItemId());

		launch.setHasRetries(launchRepository.hasRetries(launch.getId()));
		parent.ifPresent(p -> p.setHasChildren(testItemRepository.hasChildren(p.getItemId(), p.getPath())));

		logIndexer.cleanIndex(projectDetails.getProjectId(), logRepository.findIdsByTestItemId(item.getItemId()));
		eventPublisher.publishEvent(new DeleteTestItemAttachmentsEvent(item.getItemId()));

		return COMPOSE_DELETE_RESPONSE.apply(item.getItemId());
	}

	@Override
	public List<OperationCompletionRS> deleteTestItems(Collection<Long> ids, ReportPortalUser.ProjectDetails projectDetails,
			ReportPortalUser user) {
		List<TestItem> items = testItemRepository.findAllById(ids);
		List<Launch> launches = launchRepository.findAllById(items.stream().map(TestItem::getLaunchId).collect(Collectors.toSet()));

		Map<Long, List<TestItem>> launchItemMap = items.stream().collect(Collectors.groupingBy(TestItem::getLaunchId));
		launches.forEach(launch -> launchItemMap.get(launch.getId()).forEach(item -> validate(item, launch, user, projectDetails)));

		List<Long> itemsToDelete = new ArrayList<>(items.size());
		List<Long> cascadeDeletedItems = new ArrayList<>(items.size());

		Map<Integer, List<TestItem>> grouppedByLevel = items.stream()
				.collect(Collectors.groupingBy(it -> it.getType().getLevel(), TreeMap::new, Collectors.toList()));

		grouppedByLevel.forEach((key, value) -> value.stream()
				.filter(item -> !cascadeDeletedItems.contains(item.getItemId()))
				.forEach(item -> {
					itemsToDelete.add(item.getItemId());
					cascadeDeletedItems.addAll(testItemRepository.selectAllDescendantsIds(item.getPath()));
				}));

		testItemRepository.deleteAllByItemIdIn(itemsToDelete);

		launches.forEach(it -> it.setHasRetries(launchRepository.hasRetries(it.getId())));
		items.stream()
				.filter(it -> itemsToDelete.contains(it.getItemId()))
				.map(TestItem::getParent)
				.filter(Objects::nonNull)
				.forEach(it -> it.setHasChildren(false));

		logIndexer.cleanIndex(projectDetails.getProjectId(), logRepository.findIdsByTestItemIds(cascadeDeletedItems));
		items.forEach(it -> eventPublisher.publishEvent(new DeleteTestItemAttachmentsEvent(it.getItemId())));

		return cascadeDeletedItems.stream().map(COMPOSE_DELETE_RESPONSE).collect(Collectors.toList());
	}

	private static final Function<Long, OperationCompletionRS> COMPOSE_DELETE_RESPONSE = it -> new OperationCompletionRS(String.format("Test Item with ID = %d has been successfully deleted.",
			it
	));

	/**
	 * Validate {@link ReportPortalUser} credentials, {@link com.epam.ta.reportportal.entity.item.TestItemResults#status},
	 * {@link Launch#status} and {@link Launch} affiliation to the {@link com.epam.ta.reportportal.entity.project.Project}
	 *
	 * @param testItem       {@link TestItem}
	 * @param user           {@link ReportPortalUser}
	 * @param projectDetails {@link ReportPortalUser.ProjectDetails}
	 */
	private void validate(TestItem testItem, Launch launch, ReportPortalUser user, ReportPortalUser.ProjectDetails projectDetails) {
		if (user.getUserRole() != UserRole.ADMINISTRATOR) {
			expect(launch.getProjectId(), equalTo(projectDetails.getProjectId())).verify(FORBIDDEN_OPERATION, formattedSupplier(
					"Deleting testItem '{}' is not under specified project '{}'",
					testItem.getItemId(),
					projectDetails.getProjectId()
			));
			if (projectDetails.getProjectRole().lowerThan(ProjectRole.PROJECT_MANAGER)) {
				expect(user.getUserId(), Predicate.isEqual(launch.getUserId())).verify(ACCESS_DENIED, "You are not a launch owner.");
			}
		}
		expect(testItem.getRetryOf(), Objects::isNull).verify(ErrorType.RETRIES_HANDLER_ERROR,
				Suppliers.formattedSupplier("Unable to delete test item ['{}'] because it is a retry", testItem.getItemId()).get()
		);
		expect(testItem.getItemResults().getStatus(), not(it -> it.equals(StatusEnum.IN_PROGRESS))).verify(TEST_ITEM_IS_NOT_FINISHED,
				formattedSupplier("Unable to delete test item ['{}'] in progress state", testItem.getItemId())
		);
		expect(launch.getStatus(), not(it -> it.equals(StatusEnum.IN_PROGRESS))).verify(LAUNCH_IS_NOT_FINISHED, formattedSupplier(
				"Unable to delete test item ['{}'] under launch ['{}'] with 'In progress' state",
				testItem.getItemId(),
				launch.getId()
		));
	}
}
