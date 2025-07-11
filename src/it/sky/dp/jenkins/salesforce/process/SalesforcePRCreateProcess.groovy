#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.process

import it.sky.dp.jenkins.salesforce.common.Configuration

import static it.sky.dp.jenkins.salesforce.Constants.*

class SalesforcePRCreateProcess extends AbstractSalesforcePRProcess implements Serializable {

    SalesforcePRCreateProcess(def dsl) {
        super(dsl)
    }

    @Override
    void checkParameters() {
        super.checkParam(MAP_KEY_SOURCE_BRANCH_NAME)
        super.checkParam(MAP_KEY_TARGET_BRANCH_NAME)
        super.checkParam(MAP_KEY_TARGET_ENVIRONMENT)
        super.checkParam(MAP_KEY_TEST_LEVEL)
        super.checkParam(MAP_KEY_SKIP_STORE)
        super.checkParam(MAP_KEY_RELEASE_VERSION)
        super.checkParam(MAP_KEY_SKIP_SCA)
        super.checkParam(MAP_KEY_PR_DESCRIPTION)
        super.checkParam(MAP_KEY_DRY_RUN)
        super.checkParam(MAP_KEY_SKIP_INFO_COMMIT)
    }

    void checkStatus() {
        Configuration cfg = Configuration.getInstance()

        String targetBranch = cfg.getMapValue(MAP_KEY_TARGET_BRANCH_NAME)
        String sourceBranch = cfg.getMapValue(MAP_KEY_SOURCE_BRANCH_NAME)
        String repositoryUrl = cfg.getMapValue(MAP_KEY_REPOSITORY_URL)
        String repositoryCredentialId = cfg.getMapValue(MAP_KEY_REPOSITORY_CREDENTIAL_ID)

        boolean checkPullRequestExist = githubUtils.checkPullRequestExists(repositoryUrl, sourceBranch, targetBranch, repositoryCredentialId)

        if (checkPullRequestExist) {
            dsl.echo("WARNING: a pull request already exists between \"${sourceBranch}\" and \"${targetBranch}\" branches. A validate will be executed")
        } else {
            dsl.echo("Pull Request doesn't exists between \"${sourceBranch}\" and \"${targetBranch}\" branches.")
        }

        String sourceHead = gitUtils.getSourceHead(repositoryUrl, repositoryCredentialId, sourceBranch)
        cfg.addEntryToMap(MAP_KEY_GIT_SHA_BEFORE, sourceHead, true)

        boolean alreadyMerged = gitUtils.alreadyMerged(sourceHead, targetBranch, false)
        if (alreadyMerged) {
            setUnstable("Last hash \"${sourceHead}\" inside the branch \"${sourceBranch}\" has already been merged inside \"${targetBranch}\"")
        } else {
            gitUtils.mergeSources(sourceBranch)
        }
    }

    void preValidate() {
        super.preDeploy()
    }

    private void addCfgPrDetails(def prJsonResponse, boolean newPr) {
        if (prJsonResponse) {
            def jsonObjPr = this.dsl.readJSON text: prJsonResponse
            def numberPR = jsonObjPr.number
            def urlPR = jsonObjPr.html_url

            Configuration cfg = Configuration.getInstance()
            cfg.addEntryToMap(MAP_KEY_NUMBER_PR, numberPR, true)
            cfg.addEntryToMap(MAP_KEY_URL_PR, urlPR, true)

            if (newPr) {
                dsl.echo("Created PR number: ${numberPR}")
            } else {
                dsl.echo("Updated PR number: ${numberPR}")
            }
            dsl.echo("GitHub PR url: ${urlPR}")
        }
    }

    @Override
    void validate() {
        Configuration cfg = Configuration.getInstance()
        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)

        boolean doBackup = SLFC_ENV_PRODUCTION.equals(targetEnv)

        doValidate(doBackup)
    }

    void createPullRequest() {
        Configuration cfg = Configuration.getInstance()
        String repositoryUrl = cfg.getMapValue(MAP_KEY_REPOSITORY_URL)
        String repositoryCredentialId = cfg.getMapValue(MAP_KEY_REPOSITORY_CREDENTIAL_ID)
        String targetBranch = cfg.getMapValue(MAP_KEY_TARGET_BRANCH_NAME)
        String sourceBranch = cfg.getMapValue(MAP_KEY_SOURCE_BRANCH_NAME)
        String prDescription = cfg.getMapValue(MAP_KEY_PR_DESCRIPTION)
        String testLevel = cfg.getMapValue(MAP_KEY_TEST_LEVEL)
        String buildUser = cfg.getMapValue(MAP_KEY_BUILD_USER_ID)

        String sourceHash = cfg.getMapValue(MAP_KEY_GIT_SHA_BEFORE)

        String lastSourceHead = gitUtils.getSourceHead(repositoryUrl, repositoryCredentialId, sourceBranch)
        cfg.addEntryToMap(MAP_KEY_GIT_SHA_AFTER, lastSourceHead, true)

        String prDescriptionString = "Pipeline Test Execution Option: ${testLevel}\n" +
                "Pull Request opened by: ${buildUser}\n\n" + prDescription

        if (lastSourceHead.equals(sourceHash)) {
            dsl.echo "No commits has been executed during the validate"

            boolean checkPullRequestExist = githubUtils.checkPullRequestExists(repositoryUrl, sourceBranch, targetBranch, repositoryCredentialId)

            if (checkPullRequestExist) {
                def prJsonResponse = githubUtils.updatePR(repositoryUrl, sourceBranch, targetBranch, repositoryCredentialId, prDescriptionString)
                if (prJsonResponse) {
                    this.addCfgPrDetails(prJsonResponse, false)
                }
                setUnstable("A pull request already exists between \"${sourceBranch}\" and \"${targetBranch}\" branches")
            } else {
                boolean alreadyMerged = gitUtils.alreadyMerged(lastSourceHead, targetBranch, true)
                if (alreadyMerged) {
                    setUnstable("Last hash ${lastSourceHead} inside the branch ${sourceBranch} has already been merged inside ${targetBranch}, check the pull request merged")
                } else {
                    def prJsonResponse = githubUtils.createPullRequest(repositoryUrl, sourceBranch, targetBranch, repositoryCredentialId, prDescriptionString)
                    if (prJsonResponse) {
                        this.addCfgPrDetails(prJsonResponse, true)
                    }
                }
            }
        } else {
            dsl.echo "There is one new commit during the validate related to the following hash: ${lastSourceHead}"
            setUnstable("New commit during the validation, relaunching the pipeline automatically...")

            dsl.build job: cfg.getMapValue(MAP_KEY_JOB_NAME),
                    propagate: false,
                    wait: false,
                    parameters: [
                            [$class: 'StringParameterValue', name: MAP_KEY_SOURCE_BRANCH_NAME, value: cfg.getMapValue(MAP_KEY_SOURCE_BRANCH_NAME)],
                            [$class: 'StringParameterValue', name: MAP_KEY_TARGET_BRANCH_NAME, value: cfg.getMapValue(MAP_KEY_TARGET_BRANCH_NAME)],
                            [$class: 'StringParameterValue', name: MAP_KEY_TARGET_ENVIRONMENT, value: cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)],
                            [$class: 'StringParameterValue', name: MAP_KEY_TEST_LEVEL, value: cfg.getMapValue(MAP_KEY_TEST_LEVEL)],
                            [$class: 'BooleanParameterValue', name: MAP_KEY_SKIP_STORE, value: cfg.getMapValue(MAP_KEY_SKIP_STORE)],
                            [$class: 'StringParameterValue', name: MAP_KEY_RELEASE_VERSION, value: cfg.getMapValue(MAP_KEY_RELEASE_VERSION)],
                            [$class: 'StringParameterValue', name: MAP_KEY_PR_DESCRIPTION, value: cfg.getMapValue(MAP_KEY_PR_DESCRIPTION)],
                            [$class: 'BooleanParameterValue', name: MAP_KEY_DRY_RUN, value: cfg.getMapValue(MAP_KEY_DRY_RUN)],
                            [$class: 'BooleanParameterValue', name: MAP_KEY_SKIP_INFO_COMMIT, value: cfg.getMapValue(MAP_KEY_SKIP_INFO_COMMIT)]
                    ]
        }
    }

    @Override
    protected String getSlackMessageFirstPart(String buildResult) {
        String slackMessage = super.getSlackMessageFirstPart(buildResult)
        Configuration cfg = Configuration.getInstance()

        if ("SUCCESS".equals(buildResult.toUpperCase())) {
            if (cfg.getMapValue(MAP_KEY_NUMBER_PR) != null && cfg.getMapValue(MAP_KEY_URL_PR)?.trim()) {
                slackMessage += "Created Pull Request number: ${cfg.getMapValue(MAP_KEY_NUMBER_PR)}" +
                        " - <${cfg.getMapValue(MAP_KEY_URL_PR)}|${MESSAGE_OPEN_GIT_HUB}>\n"
            }
        } else {
            if ("UNSTABLE".equals(buildResult.toUpperCase())) {
                if (cfg.getMapValue(MAP_KEY_NUMBER_PR) != null && cfg.getMapValue(MAP_KEY_URL_PR)?.trim()) {
                    slackMessage += "Updated Pull Request number: ${cfg.getMapValue(MAP_KEY_NUMBER_PR)}" +
                            " - <${cfg.getMapValue(MAP_KEY_URL_PR)}|${MESSAGE_OPEN_GIT_HUB}>\n"
                }
            }
        }

        return slackMessage
    }
}
