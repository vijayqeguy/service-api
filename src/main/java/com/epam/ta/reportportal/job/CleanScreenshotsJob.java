/*
 * Copyright 2016 EPAM Systems
 *
 *
 * This file is part of EPAM Report Portal.
 * https://github.com/reportportal/service-api
 *
 * Report Portal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Report Portal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Report Portal.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.epam.ta.reportportal.job;

import com.epam.ta.reportportal.binary.DataStoreService;
import com.epam.ta.reportportal.dao.LaunchRepository;
import com.epam.ta.reportportal.dao.LogRepository;
import com.epam.ta.reportportal.dao.ProjectRepository;
import com.epam.ta.reportportal.dao.TestItemRepository;
import com.epam.ta.reportportal.entity.enums.KeepScreenshotsDelay;
import com.epam.ta.reportportal.entity.enums.ProjectAttributeEnum;
import com.epam.ta.reportportal.entity.log.Log;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.epam.ta.reportportal.commons.EntityUtils.TO_LOCAL_DATE_TIME;
import static com.epam.ta.reportportal.job.CleanLogsJob.MIN_DELAY;
import static com.epam.ta.reportportal.job.PageUtil.iterateOverPages;
import static java.time.Duration.ofDays;

/**
 * Clear screenshots from GridFS in accordance with projects settings
 *
 * @author Andrei_Ramanchuk
 */
@Service
public class CleanScreenshotsJob implements Job {
	private static final Logger LOGGER = LoggerFactory.getLogger(CleanScreenshotsJob.class);

	private final ProjectRepository projectRepository;

	private final LogCleanerService logCleaner;

	@Autowired
	public CleanScreenshotsJob(ProjectRepository projectRepository, LogCleanerService logCleaner) {
		this.projectRepository = projectRepository;
		this.logCleaner = logCleaner;
	}

	@Override
	//	@Scheduled(cron = "${com.ta.reportportal.job.clean.screenshots.cron}")
	public void execute(JobExecutionContext context) {
		LOGGER.info("Cleaning outdated screenshots has been started");

		iterateOverPages(
				pageable -> projectRepository.findAllIdsAndProjectAttributes(ProjectAttributeEnum.KEEP_SCREENSHOTS, pageable),
				projects -> projects.forEach(project -> {
					AtomicLong attachmentsCount = new AtomicLong(0);
					AtomicLong thumbnailsCount = new AtomicLong(0);

					try {
						LOGGER.info("Cleaning outdated screenshots for project {} has been started", project.getId());

						project.getProjectAttributes()
								.stream()
								.filter(pa -> pa.getAttribute()
										.getName()
										.equalsIgnoreCase(ProjectAttributeEnum.KEEP_SCREENSHOTS.getAttribute()))
								.findFirst()
								.ifPresent(pa -> {
									Duration period = ofDays(KeepScreenshotsDelay.findByName(pa.getValue()).getDays());
									if (!period.isZero()) {
										logCleaner.removeProjectAttachments(project, period, attachmentsCount, thumbnailsCount);
									}
								});

					} catch (Exception e) {
						LOGGER.info("Cleaning outdated screenshots for project {} has been failed", project.getId(), e);
					}
					LOGGER.info(
							"Cleaning outdated screenshots for project {} has been finished. {} attachments and {} thumbnails have been deleted",
							project.getId(),
							attachmentsCount.get(),
							thumbnailsCount.get()
					);
				})
		);

	}
}
