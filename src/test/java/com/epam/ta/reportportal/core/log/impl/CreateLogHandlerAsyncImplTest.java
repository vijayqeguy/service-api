package com.epam.ta.reportportal.core.log.impl;

import com.epam.ta.reportportal.commons.BinaryDataMetaInfo;
import com.epam.ta.reportportal.commons.ReportPortalUser;
import com.epam.ta.reportportal.entity.project.ProjectRole;
import com.epam.ta.reportportal.entity.user.UserRole;
import com.epam.ta.reportportal.util.ReportingQueueService;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.multipart.MultipartFile;

import javax.inject.Provider;

import static com.epam.ta.reportportal.ReportPortalUserUtil.getRpUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Konstantin Antipin
 */

@ExtendWith(MockitoExtension.class)
class CreateLogHandlerAsyncImplTest {

    @Mock
    Provider<SaveLogBinaryDataTaskAsync> provider;

    @Mock
    ReportingQueueService reportingQueueService;

    @Mock
    AmqpTemplate amqpTemplate;

    @Mock
    TaskExecutor taskExecutor;

    @InjectMocks
    CreateLogHandlerAsyncImpl createLogHandlerAsync;

    @Mock
    MultipartFile multipartFile;

    @Mock
    SaveLogBinaryDataTaskAsync saveLogBinaryDataTask;

    @Mock
    BinaryDataMetaInfo binaryDataMetaInfo;

    @Test
    void createLog() {
        SaveLogRQ request = new SaveLogRQ();
        ReportPortalUser user = getRpUser("test", UserRole.ADMINISTRATOR, ProjectRole.PROJECT_MANAGER, 1L);

        when(provider.get()).thenReturn(saveLogBinaryDataTask);
        when(saveLogBinaryDataTask.withRequest(any())).thenReturn(saveLogBinaryDataTask);
        when(saveLogBinaryDataTask.withFile(any())).thenReturn(saveLogBinaryDataTask);
        when(saveLogBinaryDataTask.withProjectId(any())).thenReturn(saveLogBinaryDataTask);

        createLogHandlerAsync.createLog(request, multipartFile, user.getProjectDetails().get("test_project"));

        verify(provider).get();
        verify(saveLogBinaryDataTask).withRequest(request);
        verify(saveLogBinaryDataTask).withFile(multipartFile);
        verify(saveLogBinaryDataTask).withProjectId(user.getProjectDetails().get("test_project").getProjectId());
    }

    @Test
    void sendMessage() {
        SaveLogRQ request = new SaveLogRQ();

        createLogHandlerAsync.sendMessage(request, binaryDataMetaInfo, 0L);
        verify(amqpTemplate).convertAndSend(any(), any(), any(), any());
        verify(reportingQueueService).getReportingQueueKey(any());
    }

}