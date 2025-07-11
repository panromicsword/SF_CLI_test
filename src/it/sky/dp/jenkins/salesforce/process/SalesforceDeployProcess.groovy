#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.process

import it.sky.dp.jenkins.salesforce.common.Configuration

import static it.sky.dp.jenkins.salesforce.Constants.*

class SalesforceDeployProcess extends AbstractSalesforceProcess implements Serializable {

    SalesforceDeployProcess(def dsl) {
        super(dsl)
    }

    void deploy() {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String slfcUrl = cfg.getMapValue(MAP_KEY_SLFC_URL)
        String credentialsId = cfg.getMapValue(MAP_KEY_SF_CREDENTIALS_ID)
        String testLevel = cfg.getMapValue(MAP_KEY_TEST_LEVEL_VALIDATE)
        def testClsList = cfg.getMapValue(MAP_KEY_TEST_CLASSES)
        boolean isQuickDeploy = cfg.getMapValue(MAP_KEY_IS_QUICK_DEPLOY)

        if (isQuickDeploy) {
            String quickDeployId = cfg.getMapValue(MAP_KEY_QUICK_DEPLOY_ID)

            slfcUtils.quickDeploy(workingPath, slfcUrl, credentialsId, quickDeployId)
        } else {
            slfcUtils.deploy(workingPath, slfcUrl, credentialsId, testLevel, testClsList)
        }
    }

    void deployVlocity() {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String slfcUrl = cfg.getMapValue(MAP_KEY_SLFC_URL)
        String credentialsId = cfg.getMapValue(MAP_KEY_SF_CREDENTIALS_ID)
        boolean vlocityEnabled = cfg.getMapValue(MAP_KEY_VLOCITY_ENABLED)
        assert (vlocityEnabled)

        vlocityUtils.deploy(workingPath, slfcUrl, credentialsId)
    }

    @Override
    void checkParameters() {
        super.checkParam(MAP_KEY_BRANCH_NAME)
        super.checkParam(MAP_KEY_START_HASH)
        super.checkParam(MAP_KEY_END_HASH)
        super.checkParam(MAP_KEY_VALIDATE_ONLY)
        super.checkParam(MAP_KEY_TARGET_ENVIRONMENT)
        super.checkParam(MAP_KEY_TEST_LEVEL)
        super.checkParam(MAP_KEY_RELEASE_VERSION)
        super.checkParam(MAP_KEY_SKIP_SCA)
        super.checkParam(MAP_KEY_QUICK_DEPLOY_ID)
        super.checkParam(MAP_KEY_SKIP_INFO_COMMIT)
    }

    @Override
    void prepareBuildDescription() {
        super.prepareBuildDescription()
        Configuration cfg = Configuration.getInstance()

        String actionType = (cfg.getMapValue(MAP_KEY_VALIDATE_ONLY)) ? "Validate" : "Deploy"
        buildDescr.add("Run Mode: ${actionType}")
    }

    void tagSource() {
        Configuration cfg = Configuration.getInstance()
        String repositoryCredentialId = cfg.getMapValue(MAP_KEY_REPOSITORY_CREDENTIAL_ID)

        String tagName = cfg.getMapValue(MAP_KEY_DEPLOY_TAG_NAME)
        gitUtils.tagSource(repositoryCredentialId, tagName)
    }

    void tagSourceVlocity() {
        Configuration cfg = Configuration.getInstance()
        String repositoryCredentialId = cfg.getMapValue(MAP_KEY_REPOSITORY_CREDENTIAL_ID)
        boolean vlocityEnabled = cfg.getMapValue(MAP_KEY_VLOCITY_ENABLED)
        assert (vlocityEnabled)

        String tagName = cfg.getMapValue(MAP_KEY_VLOCITY_DEPLOY_TAG_NAME)
        gitUtils.tagSource(repositoryCredentialId, tagName)
    }

    void postMPCheck() {
        Configuration cfg = Configuration.getInstance()
        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)
        Map<String, ArrayList<ArrayList<String>>> mpMap = cfg.getMapValue(MAP_KEY_MANUAL_PROCEDURES)

        String mpString = slfcUtils.getManualProceduresToBeExecutedStr(mpMap, STAGE_POST, targetEnv)
        dsl.echo(mpString)

        def approver = dsl.input(submitterParameter: 'approver',
                message: "Please perform all manual procedures listed in the log for the step POST ${targetEnv}",
                ok: "All manual procedures performed"
        )
        cfg.addEntryToMap(MAP_KEY_MANUAL_PROCEDURES_POST_APPROVER, approver, true)
    }

    @Override
    protected String getSlackMessageFirstPart(String buildResult) {
        String slackMessage = super.getSlackMessageFirstPart(buildResult)
        Configuration cfg = Configuration.getInstance()

        if ("SUCCESS".equals(buildResult.toUpperCase()) || "UNSTABLE".equals(buildResult.toUpperCase())) {
            if (cfg.getMapValue(MAP_KEY_NEXUS_ARTIFACT_URL) != null && cfg.getMapValue(MAP_KEY_NEXUS_ARTIFACT_URL)?.trim()) {
                slackMessage += "<${cfg.getMapValue(MAP_KEY_NEXUS_ARTIFACT_URL)}|${MESSAGE_DOWNLOAD_NEXUS_ARTIFACT}>\n"
            }
        }

        return slackMessage
    }

}
