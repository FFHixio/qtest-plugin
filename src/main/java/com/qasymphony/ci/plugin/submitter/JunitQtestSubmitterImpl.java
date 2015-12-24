package com.qasymphony.ci.plugin.submitter;

import com.qasymphony.ci.plugin.AutomationTestService;
import com.qasymphony.ci.plugin.ConfigService;
import com.qasymphony.ci.plugin.OauthProvider;
import com.qasymphony.ci.plugin.exception.StoreResultException;
import com.qasymphony.ci.plugin.exception.SubmittedException;
import com.qasymphony.ci.plugin.model.AutomationTestResponse;
import com.qasymphony.ci.plugin.model.Configuration;
import com.qasymphony.ci.plugin.model.SubmittedResult;
import com.qasymphony.ci.plugin.model.qtest.SubmittedTask;
import com.qasymphony.ci.plugin.store.StoreResultService;
import com.qasymphony.ci.plugin.store.StoreResultServiceImpl;
import com.qasymphony.ci.plugin.utils.JsonUtils;
import com.qasymphony.ci.plugin.utils.LoggerUtils;
import com.qasymphony.ci.plugin.utils.ResponseEntity;
import hudson.model.AbstractBuild;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.lang.StringUtils;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author trongle
 * @version 10/21/2015 2:09 PM trongle $
 * @since 1.0
 */
public class JunitQtestSubmitterImpl implements JunitSubmitter {
  private static final Logger LOG = Logger.getLogger(JunitQtestSubmitterImpl.class.getName());
  private StoreResultService storeResultService = new StoreResultServiceImpl();

  /**
   * Retry interval for get task status in 1 second
   */
  private static final Integer RETRY_INTERVAL = 1000;

  /**
   * State list which marked as submission have been finished
   */
  private static final List<String> LIST_FINISHED_STATE = Arrays.asList("SUCCESS", "FAILED");

  @Override public JunitSubmitterResult submit(JunitSubmitterRequest request) throws Exception {
    String accessToken = OauthProvider.getAccessToken(request.getConfiguration().getUrl(), request.getConfiguration().getAppSecretKey());
    if (StringUtils.isEmpty(accessToken))
      throw new SubmittedException(String.format("Cannot get access token from: %s, access token is: %s",
        request.getConfiguration().getUrl(), request.getConfiguration().getAppSecretKey()));

    ResponseEntity responseEntity = AutomationTestService.push(request.getBuildNumber(), request.getBuildPath(),
      request.getTestResults(), request.getConfiguration(), OauthProvider.buildHeaders(accessToken, null));
    AutomationTestResponse response = null;
    if (responseEntity.getStatusCode() == HttpStatus.SC_CREATED) {
      //receive task response
      SubmittedTask task = JsonUtils.fromJson(responseEntity.getBody(), SubmittedTask.class);
      if (task == null || task.getId() <= 0)
        throw new SubmittedException(responseEntity.getBody(), responseEntity.getStatusCode());
      response = getSubmitLogResponse(request, task);
    } else {
      //if cannot passed validation from qTest
      throw new SubmittedException(ConfigService.getErrorMessage(responseEntity.getBody()), responseEntity.getStatusCode());
    }

    JunitSubmitterResult result = new JunitSubmitterResult()
      .setSubmittedStatus(JunitSubmitterResult.STATUS_FAILED)
      .setNumberOfTestResult(request.getTestResults().size())
      .setTestSuiteId(null)
      .setTestSuiteName("")
      .setNumberOfTestLog(0);
    if (response == null)
      return result;

    result.setSubmittedStatus(JunitSubmitterResult.STATUS_SUCCESS)
      .setTestSuiteId(response.getTestSuiteId())
      .setTestSuiteName(response.getTestSuiteName())
      .setNumberOfTestLog(response.getTotalTestLogs());
    return result;
  }

  private AutomationTestResponse getSubmitLogResponse(JunitSubmitterRequest request, SubmittedTask task)
    throws InterruptedException, SubmittedException {
    if (task == null || task.getId() <= 0)
      return null;

    AutomationTestResponse response = null;
    PrintStream logger = request.getListener().getLogger();
    Map<String, String> headers = OauthProvider.buildHeaders(request.getConfiguration().getUrl(), request.getConfiguration().getAppSecretKey(), null);
    Boolean mustRetry = true;
    String previousState = "";
    while (mustRetry) {
      response = getTaskResponse(request, task, headers);
      if (null == response) {
        LoggerUtils.formatError(logger, "Cannot get response of taskId: %s", task.getId());
        mustRetry = false;
      } else {
        if (!previousState.equalsIgnoreCase(response.getState())) {
          LoggerUtils.formatInfo(logger, "%s: Submission status: %s", JsonUtils.getCurrentDateString(), response.getState());
          previousState = StringUtils.isEmpty(response.getState()) ? "" : response.getState();
        }
        if (response.hasError()) {
          //if has error while get task status
          LoggerUtils.formatError(logger, "   %s", ConfigService.getErrorMessage(response.getContent()));
        }
        if (LIST_FINISHED_STATE.contains(response.getState())) {
          //if finished, we do not retry more
          mustRetry = false;
        } else {
          //sleep in interval to get status of task
          Thread.sleep(RETRY_INTERVAL);
        }
      }
    }
    return response;
  }

  private AutomationTestResponse getTaskResponse(JunitSubmitterRequest request, SubmittedTask task, Map<String, String> headers)
    throws SubmittedException {
    AutomationTestResponse response;
    try {
      //get task status
      ResponseEntity responseEntity = AutomationTestService.getTaskStatus(request.getConfiguration(), task.getId(), headers);
      if (responseEntity.getStatusCode() != HttpStatus.SC_OK) {
        //if error while get status from qTest
        throw new SubmittedException(ConfigService.getErrorMessage(responseEntity.getBody()), responseEntity.getStatusCode());
      }
      LOG.info(String.format("status:%s, body:%s", responseEntity.getStatusCode(), responseEntity.getBody()));
      response = new AutomationTestResponse(responseEntity.getBody());
    } catch (Exception e) {
      throw new SubmittedException(e.getMessage(), -1);
    }
    return response;
  }

  @Override public SubmittedResult storeSubmittedResult(AbstractBuild build, JunitSubmitterResult result)
    throws StoreResultException {
    //get saved configuration
    Configuration configuration = ConfigService.getPluginConfiguration(build.getProject());
    String qTestUrl = configuration == null ? "" : configuration.getUrl();
    Long projectId = configuration == null ? 0L : configuration.getProjectId();

    SubmittedResult submitResult = new SubmittedResult()
      .setUrl(qTestUrl)
      .setProjectId(projectId)
      .setBuildNumber(build.getNumber())
      .setStatusBuild(build.getResult().toString())
      .setTestSuiteId(result.getTestSuiteId())
      .setTestSuiteName(result.getTestSuiteName())
      .setSubmitStatus(result.getSubmittedStatus())
      .setNumberTestLog(result.getNumberOfTestLog())
      .setNumberTestResult(result.getNumberOfTestResult());
    try {
      storeResultService.store(build.getProject(), submitResult);
      return submitResult;
    } catch (Exception e) {
      LOG.log(Level.WARNING, e.getMessage(), e);
      throw new StoreResultException("Cannot store result." + e.getMessage(), e);
    }
  }
}
