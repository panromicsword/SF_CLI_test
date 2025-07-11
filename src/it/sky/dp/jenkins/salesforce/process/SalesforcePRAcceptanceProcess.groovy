#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.process

import it.sky.dp.jenkins.salesforce.common.Configuration

import static it.sky.dp.jenkins.salesforce.Constants.*

class SalesforcePRAcceptanceProcess extends AbstractSalesforcePRProcess implements Serializable {

    SalesforcePRAcceptanceProcess(def dsl) {
        super(dsl)
    }

    void preInitVariables() {
        super.initRepositoryVariables()

        Configuration cfg = Configuration.getInstance()
        String sourceBranch = cfg.getMapValue(MAP_KEY_SOURCE_BRANCH_NAME)
        String targetBranch = cfg.getMapValue(MAP_KEY_TARGET_BRANCH_NAME)
        String repositoryUrl = cfg.getMapValue(MAP_KEY_REPOSITORY_URL)
        String repositoryCredentialId = cfg.getMapValue(MAP_KEY_REPOSITORY_CREDENTIAL_ID)

        def prMap = githubUtils.getPullRequests(repositoryUrl, sourceBranch, targetBranch, repositoryCredentialId)

        String errorMsg = "No pull request mergeable for target branch \"" + targetBranch + "\"."
        if (!prMap.isEmpty()) {
            def prMergeArr = []
            def prNoMergeArr = []

            prMap.each { key, value ->
                boolean isMergeable = githubUtils.isPullRequestMergeable(repositoryUrl, "" + key, repositoryCredentialId)
                if (isMergeable) {
                    prMergeArr.add("KEY: " + key + " |@| NAME: " + value['Title job'].replace(",", "") + " |@| BRANCH: " + value['Source_Branch'] + "")
                } else {
                    prNoMergeArr.add(key + "-" + value['Title job'] + " (" + value['Source_Branch'] + " branch)")
                }
            }

            if (prMergeArr.size() > 1) {
                prMergeArr = prMergeArr.sort()
            }
            if (prMergeArr.size() == 0) {
                if (prNoMergeArr.size() > 0) {
                    errorMsg += "\nAvailable only not mergeable pull requests: " + prNoMergeArr.join(",")
                }
                dsl.error(errorMsg)
            }

            cfg.addEntryToMap(MAP_KEY_AVAILABLE_PRS_MERGE, prMergeArr.join(","), false)
            cfg.addEntryToMap(MAP_KEY_AVAILABLE_PRS_NOMERGE, prNoMergeArr.join(","), false)
        } else {
            dsl.error(errorMsg)
        }
    }

    @Override
    void checkParameters() {
        super.checkParam(MAP_KEY_TARGET_BRANCH_NAME)
        super.checkParam(MAP_KEY_TARGET_ENVIRONMENT)
        super.checkParam(MAP_KEY_TEST_LEVEL)
        super.checkParam(MAP_KEY_RELEASE_VERSION)
        super.checkParam(MAP_KEY_AVAILABLE_PRS)
        super.checkParam(MAP_KEY_SKIP_VALIDATE)
    }

    private Map getPullRequestMapFromAvailablePRs(String availablePrs) {
        def prMap = [:]

        String[] availablePrsArr = availablePrs.split(",")
        availablePrsArr.each { pr ->
            String[] prArr = pr.split("\\|@\\|")
            if (prArr.length == 3) {
                //String prKey = prArr[0].replace("KEY:", "").trim()
                String prName = prArr[1].replace("NAME:", "").trim()
                String prSourceBranch = prArr[2].replace("BRANCH:", "").trim()

                prMap.put(prName, prSourceBranch)
            } else {
                dsl.error("An error was occured while retrieving elements for pull request: " + pr)
            }
        }

        return prMap
    }

    @Override
    void validate() {
        doValidate(false)
    }

    private PullRequestMergeResult mergePullRequestInternal(String prName, String prSourceBranch) {
        Configuration cfg = Configuration.getInstance()
        String prTargetBranchName = cfg.getMapValue(MAP_KEY_TARGET_BRANCH_NAME)
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String repositoryUrl = cfg.getMapValue(MAP_KEY_REPOSITORY_URL)
        String repositoryCredentialId = cfg.getMapValue(MAP_KEY_REPOSITORY_CREDENTIAL_ID)

        dsl.echo("-------- Merging Pull Request \"${prName}\" --------")

        PullRequestMergeResult result = PullRequestMergeResult.ok
        String pullRequestNumber = this.getPullRequestNumber(repositoryUrl, prSourceBranch, prTargetBranchName, repositoryCredentialId)
        cfg.addEntryToMap(MAP_KEY_NUMBER_PR, pullRequestNumber, true)

        if (!pullRequestNumber?.trim()) {
            result = PullRequestMergeResult.notExists
        } else {
            boolean isPROk = this.getPullRequestStatus(repositoryUrl, pullRequestNumber, repositoryCredentialId)

            if (!isPROk) {
                result = PullRequestMergeResult.notMergeable
            }
        }

        if (result == PullRequestMergeResult.ok) {
            gitUtils.cleanBranch(prTargetBranchName, true, workingPath)

            String sourceHeadBefore = gitUtils.getSourceHead(repositoryUrl, repositoryCredentialId, prSourceBranch)
            dsl.echo "sourceHeadBefore: ${sourceHeadBefore}"
            cfg.addEntryToMap(MAP_KEY_GIT_SHA_BEFORE, sourceHeadBefore, true)

            try {
                // local merge (source branch -> target branch)
                gitUtils.mergeSources(prSourceBranch)
            } catch (Exception e) {
                result = PullRequestMergeResult.errorMergingLocal
                setUnstable("Error merging locally")
            }

            if (result == PullRequestMergeResult.ok && !cfg.getMapValue(MAP_KEY_SKIP_VALIDATE)) {
                try {
                    // git-diff actions
                    dsl.echo("mergePullRequestInternal: git-diff actions")
                    cfg.addEntryToMap(MAP_KEY_SKIP_INFO_COMMIT, true, true)
                    super.preDeploy()
                    dsl.echo("mergePullRequestInternal: git-diff actions end")

                    // convert sources
                    boolean deltaForceAppExists = cfg.getMapValue(MAP_KEY_DELTA_FORCEAPP_EXISTS)
                    if (deltaForceAppExists) {
                        dsl.echo("mergePullRequestInternal: convert sources")
                        super.convertSources()
                        dsl.echo("mergePullRequestInternal: convert sources end")
                    } else {
                        dsl.echo("mergePullRequestInternal: convert sources skip")
                    }

                    // pre-mp check
                    boolean manualProceduresExists = cfg.getMapValue(MAP_KEY_DELTA_PRE_MANUAL_PROCEDURES_EXISTS)
                    if (manualProceduresExists) {
                        dsl.echo("mergePullRequestInternal: pre-mp check")
                        super.preMPCheck()
                        dsl.echo("mergePullRequestInternal: pre-mp check end")
                    } else {
                        dsl.echo("mergePullRequestInternal: pre-mp check skip")
                    }

                    // validate sources
                    if (deltaForceAppExists) {
                        dsl.echo("mergePullRequestInternal: validate sources")
                        validate()
                        dsl.echo("mergePullRequestInternal: validate sources end")

                        String deployId = slfcUtils.getDeployId(workingPath, pullRequestNumber)
                        String oldDeployId = cfg.getMapValue(MAP_KEY_ACTUAL_DEPLOY_ID)

                        oldDeployId = (oldDeployId?.trim()) ? oldDeployId.trim() : ""
                        deployId = "${oldDeployId}\n${deployId}".trim()

                        cfg.addEntryToMap(MAP_KEY_ACTUAL_DEPLOY_ID, deployId, true)
                    } else {
                        dsl.echo("mergePullRequestInternal: validate sources skip")
                    }
                } catch (Exception e) {
                    result = PullRequestMergeResult.errorValidation
                    setUnstable("Error in validation: " + e.getMessage())
                }
            }

            if (result == PullRequestMergeResult.ok) {
                String sourceHeadAfter = gitUtils.getSourceHead(repositoryUrl, repositoryCredentialId, prSourceBranch)
                dsl.echo "sourceHeadAfter: ${sourceHeadAfter}"
                cfg.addEntryToMap(MAP_KEY_GIT_SHA_AFTER, sourceHeadAfter, true)

                if (!sourceHeadAfter.equals(sourceHeadBefore)) {
                    result = PullRequestMergeResult.newCommit
                    setUnstable("New commit on ${sourceHeadBefore} during the validation")
                } else {
                    // check again if pr is ok (some time is elapsed due to validate)
                    boolean isPROk = this.getPullRequestStatus(repositoryUrl, pullRequestNumber, repositoryCredentialId)

                    if (!isPROk) {
                        result = PullRequestMergeResult.notMergeable
                    }

                    if (result == PullRequestMergeResult.ok) {
                        boolean mergePullRequest = githubUtils.mergePullRequest(repositoryUrl, pullRequestNumber, repositoryCredentialId)
                        if (mergePullRequest) {
                            result = PullRequestMergeResult.ok
                            dsl.echo "Pull request merged successfully"
                        } else {
                            result = PullRequestMergeResult.errorMergingGitHub
                            setUnstable("Errors merging pull request between ${prTargetBranchName} and ${prSourceBranch}")
                        }
                    }
                }
            }
        }
        return result
    }

    private String getPullRequestNumber(String repositoryUrl, String sourceBranch, String targetBranch, String repositoryCredentialId) {
        String result = null

        def prMap = githubUtils.getPullRequests(repositoryUrl, sourceBranch, targetBranch, repositoryCredentialId)

        if (prMap.isEmpty()) {
            setUnstable("Pull Request doesn't exists between \"${sourceBranch}\" and \"${targetBranch}\" branches.")
        } else {
            result = prMap.keySet()[0]
        }

        return result
    }

    private boolean getPullRequestStatus(String repositoryUrl, String pullRequestNumber, String repositoryCredentialId) {
        boolean result = false

        boolean isMergeable = githubUtils.isPullRequestMergeable(repositoryUrl, pullRequestNumber, repositoryCredentialId)
        if (isMergeable) {
            result = true
        } else {
            setUnstable("Pull Request n. \"${pullRequestNumber}\" is not mergeable")
        }

        return result
    }

    private void manageMergePullRequestResult(PullRequestMergeResult result, def successPrMap, def failedPrMap, def retryPrMap, String key, String value) {
        dsl.echo("Result: " + result.toString() + " for pull request \"" + key + "\" of branch \"" + value + "\"")

        switch (result) {
            case PullRequestMergeResult.ok:
                successPrMap.put(key, value)
                break

            case PullRequestMergeResult.notExists:
            case PullRequestMergeResult.notMergeable:
            case PullRequestMergeResult.errorMergingLocal:
            case PullRequestMergeResult.errorMergingGitHub:
                failedPrMap.put(key, value)
                break

            case PullRequestMergeResult.errorValidation:
            case PullRequestMergeResult.newCommit:
                retryPrMap.put(key, value)
                break
        }
    }

    void mergePullRequest() {
        Configuration cfg = Configuration.getInstance()
        String availablePrs = cfg.getMapValue(MAP_KEY_AVAILABLE_PRS)

        def originalPrMap = this.getPullRequestMapFromAvailablePRs(availablePrs)

        dsl.echo("Retrieved pull request map -> " + originalPrMap)

        def successPrMap = [:]
        def failedPrMap = [:]
        def retryPrMap = [:]

        originalPrMap.each { key, value ->
            PullRequestMergeResult result = this.mergePullRequestInternal(key, value)

            this.manageMergePullRequestResult(result, successPrMap, failedPrMap, retryPrMap, key, value)
        }

        int totalCycles = 3
        if (!retryPrMap.isEmpty()) {
            for (int i = 1; i <= totalCycles; i++) {
                dsl.echo("Cycle retry ${i}/${totalCycles}")
                boolean onePRHasMerged = false
                def tmpRetryPrMap = retryPrMap.clone()
                retryPrMap = [:]

                tmpRetryPrMap.each { key, value ->
                    PullRequestMergeResult result = this.mergePullRequestInternal(key, value)

                    if (result == PullRequestMergeResult.ok) {
                        onePRHasMerged = true
                    }

                    this.manageMergePullRequestResult(result, successPrMap, failedPrMap, retryPrMap, key, value)
                }

                if (!onePRHasMerged) {
                    dsl.echo("No pull request has been merged during the cycle so a forced break is done")
                    break
                }
                if (retryPrMap.isEmpty()) {
                    dsl.echo("All retry pull request has been managed before the total of recycles")
                    break
                }
            }
        }

        String printResult = "-------- FINAL PULL REQUEST MERGE RESULT --------\n" +
                "-------------------------------------------------"
        // success pr
        if (!successPrMap.isEmpty()) {
            printResult += "\nSuccess pull requests:\n"
            successPrMap.each { key, value ->
                printResult += "\t- ${key} on branch ${value}\n"
            }
        }
        // failed pr
        if (!failedPrMap.isEmpty()) {
            printResult += "\nFailed pull requests:\n"
            failedPrMap.each { key, value ->
                printResult += "\t- ${key} on branch ${value}\n"
            }
        }
        // retry pr
        if (!retryPrMap.isEmpty()) {
            printResult += "\nNot completed pull requests due to recycles or other issue (see logs):\n"
            retryPrMap.each { key, value ->
                printResult += "\t- ${key} on branch ${value}\n"
            }
        }

        dsl.echo(printResult)

        cfg.addEntryToMap(MAP_KEY_SLACK_MESSAGE, printResult, false)

        if (!originalPrMap.isEmpty() && successPrMap.isEmpty()) {
            dsl.error("No pull request has been successfully merged, please see the logs for better understand the issue")
        }
    }
}

enum PullRequestMergeResult {
    ok,
    newCommit,
    notMergeable,
    errorMergingGitHub,
    errorValidation,
    errorMergingLocal,
    notExists
}