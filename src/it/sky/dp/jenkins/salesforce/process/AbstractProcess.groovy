package it.sky.dp.jenkins.salesforce.process

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.common.Utils
import it.sky.dp.jenkins.salesforce.scm.GitUtils
import it.sky.dp.jenkins.salesforce.technology.DockerUtils
import net.sf.json.JSONArray

import static it.sky.dp.jenkins.salesforce.Constants.*

abstract class AbstractProcess {
    protected def dsl
    protected Utils utils
    protected GitUtils gitUtils
    protected DockerUtils dockerUtils

    protected def buildDescr = []

    AbstractProcess(def dsl) {
        this.dsl = dsl
        this.utils = new Utils(dsl)
        this.gitUtils = new GitUtils(dsl)
        this.dockerUtils = new DockerUtils(dsl)
    }

    void cleanWorkspace() {
        dsl.cleanWs()
    }

    void setUnstable(String message) {
        Configuration cfg = Configuration.getInstance()
        cfg.addEntryToMap(MAP_KEY_UNSTABLE_MESSAGE, message, true)

        dsl.unstable(message)
    }

    protected void checkParam(String mapKey) {
        Configuration cfg = Configuration.getInstance()
        def map = cfg.getMap()
        if (!map.containsKey(mapKey)) {
            dsl.error("Configuration value for key \"${mapKey}\" not found")
        }
    }

    abstract void checkParameters()

    void initProcessVariables() {
        // nothing to do
    }

    void prepareBuildDescription() {
        // nothing to do
    }

    void initRepositoryVariables() {
        Configuration cfg = Configuration.getInstance()

        dsl.echo("PROJECT_URL: " + dsl.env.PROJECT_URL)

        cfg.addEntryToMap(MAP_KEY_REPOSITORY_URL, dsl.env.PROJECT_URL, true)
        cfg.addEntryToMap(MAP_KEY_REPOSITORY_CREDENTIAL_ID, dsl.scm.userRemoteConfigs[0].credentialsId, true)
    }

    void initVariables() {
        Configuration cfg = Configuration.getInstance()
        dsl.echo("Init configuration map->\n\n" + cfg.toString() + "\n")

        def map = cfg.getMap()
        cfg.addEntryToMap(MAP_KEY_JOB_INPUT_PARAMS, map.clone(), true)

        this.checkParameters() // class specific

        String fixedJobName = dsl.env.JOB_NAME
        fixedJobName = fixedJobName.substring(fixedJobName.lastIndexOf('/') + 1) // for lab purpose

        cfg.addEntryToMap(MAP_KEY_JOB_NAME, fixedJobName, true)
        cfg.addEntryToMap(MAP_KEY_WORKING_PATH, dsl.env.WORKSPACE, true)
        cfg.addEntryToMap(MAP_KEY_BLUE_OCEAN_URL, dsl.env.RUN_DISPLAY_URL, true)

        initRepositoryVariables()

        initProcessVariables() // class specific

        dsl.echo("End configuration map->\n\n" + cfg.toString() + "\n")

        def specificCause = dsl.currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
        if (specificCause) {
            buildDescr.add("User: ${specificCause.userId[0]}")
        }
        this.prepareBuildDescription() // class specific
        String descr = ""
        if (cfg.getMapValue(MAP_KEY_BRANCH_NAME) != null && cfg.getMapValue(MAP_KEY_BRANCH_NAME)?.trim()) {
            descr = "${cfg.getMapValue(MAP_KEY_BRANCH_NAME)} - "
        }
        descr += "#${dsl.env.BUILD_NUMBER}"
        dsl.currentBuild.displayName = descr
        dsl.currentBuild.description = buildDescr.join("\n")

        gitUtils.setConfigs()
        utils.checkDiskSpace()
    }

    void checkoutSources() {
        Configuration cfg = Configuration.getInstance()
        String branchName = cfg.getMapValue(MAP_KEY_BRANCH_NAME)
        String repositoryUrl = cfg.getMapValue(MAP_KEY_REPOSITORY_URL)
        String repositoryCredentialId = cfg.getMapValue(MAP_KEY_REPOSITORY_CREDENTIAL_ID)
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)

        gitUtils.checkoutBranch(repositoryUrl, branchName, repositoryCredentialId, workingPath)
        gitUtils.setRemoteUrl(repositoryUrl, repositoryCredentialId)
        utils.checkDiskSpace()
    }

    protected void loadSalesforceConfig() {
        Configuration cfg = Configuration.getInstance()
        def slfcConfigs = cfg.getMapValue(MAP_KEY_SF_CONFIGS)
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)

        if (!slfcConfigs) {
            //dsl.sh("find . -type f -print0 | xargs -0 grep 'salesforce-configs'")
            if (dsl.fileExists("${workingPath}/devops/salesforce-configs.json")) {
                slfcConfigs = dsl.readJSON file: "${workingPath}/devops/salesforce-configs.json"
                cfg.addEntryToMap(MAP_KEY_SF_CONFIGS, slfcConfigs, true)
                dsl.echo("File \"salesforce-configs.json\" loaded successfully")
                dsl.echo("\n" + slfcConfigs + "\n")

                cfg.addEntryToMap(MAP_KEY_PROJECT_NAME, slfcConfigs.projectName, true)

                def developmentEnvironments
                if (slfcConfigs.testEnvironments) {
                    assert (!(SLFC_ENV_PRODUCTION in slfcConfigs.testEnvironments)): "\"${SLFC_ENV_PRODUCTION}\" not allowed in test envs"
                    assert (!(SLFC_ENV_PRODRYRUN in slfcConfigs.testEnvironments)): "\"${SLFC_ENV_PRODRYRUN}\" not allowed in test envs"
                    developmentEnvironments = slfcConfigs.testEnvironments
                } else {
                    developmentEnvironments = DEFAULT_TEST_ENVIRONMENTS
                }
                cfg.addEntryToMap(MAP_KEY_TEST_ENVIRONMENTS, developmentEnvironments, true)
                cfg.addEntryToMap(MAP_KEY_PROD_ENVIRONMENTS, [SLFC_ENV_PRODUCTION], true)

                if (!slfcConfigs.vendors || !slfcConfigs.vendors instanceof JSONArray) {
                    dsl.error("\"vendors\" element not found into salesforce-configs.json")
                }

                def sourceAlertWarning = []
                def sourceAlertError = []
                if (!slfcConfigs.sourceAlert) {
                    dsl.echo("No \"sourceAlert\" element not found into salesforce-configs.json")
                } else {
                    // warning
                    if (slfcConfigs.sourceAlert.warning) {
                        if (slfcConfigs.sourceAlert.warning instanceof JSONArray) {
                            slfcConfigs.sourceAlert.warning.each { warn ->
                                sourceAlertWarning.push(warn)
                            }
                        } else {
                            sourceAlertWarning.push(slfcConfigs.sourceAlert.warning)
                        }
                    } else {
                        dsl.echo("No \"sourceAlert.warning\" element not found into salesforce-configs.json")
                    }
                    // error
                    if (slfcConfigs.sourceAlert.error) {
                        if (slfcConfigs.sourceAlert.error instanceof JSONArray) {
                            slfcConfigs.sourceAlert.error.each { err ->
                                sourceAlertError.push(err)
                            }
                        } else {
                            sourceAlertError.push(slfcConfigs.sourceAlert.error)
                        }
                    } else {
                        dsl.echo("No \"sourceAlert.error\" element not found into salesforce-configs.json")
                    }
                }
                cfg.addEntryToMap(MAP_KEY_SOURCE_ALERT_WARNING, sourceAlertWarning, true)
                cfg.addEntryToMap(MAP_KEY_SOURCE_ALERT_ERROR, sourceAlertError, true)

                if (slfcConfigs.tools) {
                    if (!slfcConfigs.tools.salesforce) {
                        dsl.error("\"tools.salesforce\" element not found into salesforce-configs.json")
                    } else {
                        cfg.addEntryToMap(MAP_KEY_SALESFORCE_VERSION, slfcConfigs.tools.salesforce.version, true)
                    }
                    if (!slfcConfigs.tools.docker) {
                        dsl.error("\"tools.docker\" element not found into salesforce-configs.json")
                    }
                    if (!slfcConfigs.tools.nexus) {
                        dsl.error("\"tools.nexus\" element not found into salesforce-configs.json")
                    }
                    if (!slfcConfigs.tools.elasticsearch) {
                        dsl.echo("WARNING: \"tools.elasticsearch\" element not found into salesforce-configs.json")
                    }
                } else {
                    dsl.error("\"tools\" element not found into salesforce-configs.json")
                }

                if (slfcConfigs.devopsOpts) {
                    cfg.addEntryToMap(MAP_KEY_DOCKER_BUILD_SKIP, slfcConfigs.devopsOpts.skipDockerBuild, false)
                    cfg.addEntryToMap(MAP_KEY_DOCKER_PULL_SKIP, slfcConfigs.devopsOpts.skipDockerPull, false)
                } else {
                    cfg.addEntryToMap(MAP_KEY_DOCKER_BUILD_SKIP, false, false)
                    cfg.addEntryToMap(MAP_KEY_DOCKER_PULL_SKIP, false, false)
                }
            } else {
                dsl.sleep(120)
                dsl.sh("pwd")
                dsl.error("Error during loading \"salesforce-configs.json\" file")
            }
        }

        boolean vlocityExists = dsl.fileExists("${workingPath}/vlocity_components")
        dsl.echo("vlocityExists->" + vlocityExists)
        cfg.addEntryToMap(MAP_KEY_VLOCITY_ENABLED, vlocityExists, false)
    }

    protected void loadDockerConfig() {
        Configuration cfg = Configuration.getInstance()
        def slfcConfigs = cfg.getMapValue(MAP_KEY_SF_CONFIGS)

        if (slfcConfigs) {
            String dockerfilePath = slfcConfigs.tools.docker.dockerfilePath
            String imageName = slfcConfigs.tools.docker.containerName
            String imageTagPrefix = slfcConfigs.tools.docker.containerTagPrefix
            String salesforceVersion = "" + cfg.getMapValue(MAP_KEY_SALESFORCE_VERSION)
            String imageTag = imageTagPrefix + "-" + salesforceVersion

            cfg.addEntryToMap(MAP_KEY_DOCKER_IMAGE_NAME, imageName, true)
            cfg.addEntryToMap(MAP_KEY_DOCKER_IMAGE_TAG, imageTag, true)
            cfg.addEntryToMap(MAP_KEY_DOCKER_IMAGE_FULLNAME, imageName + ":" + imageTag, true)

            if (slfcConfigs.tools.docker.registry) {
                cfg.addEntryToMap(MAP_KEY_DOCKER_REGISTRY_PROTOCOL, slfcConfigs.tools.docker.registry.protocol, true)
                cfg.addEntryToMap(MAP_KEY_DOCKER_REGISTRY_ACCOUNT_ID, slfcConfigs.tools.docker.registry.accountId, true)
                cfg.addEntryToMap(MAP_KEY_DOCKER_REGISTRY_ENDPOINT, slfcConfigs.tools.docker.registry.endpoint, true)
                cfg.addEntryToMap(MAP_KEY_DOCKER_REGISTRY_REGION, slfcConfigs.tools.docker.registry.region, true)
            } else {
                dsl.error("Registry object not found in \"salesforce-configs.json\"")
            }

            cfg.addEntryToMap(MAP_KEY_DOCKERFILE_PATH, dockerfilePath, true)
        } else {
            dsl.error("Error during loading slfcConfigs object")
        }
    }

    void loadConfigs() {
        loadSalesforceConfig()
        loadDockerConfig()
    }

    void pullDockerImage() {
        Configuration cfg = Configuration.getInstance()

        String imageName = cfg.getMapValue(MAP_KEY_DOCKER_IMAGE_NAME)
        String imageTag = cfg.getMapValue(MAP_KEY_DOCKER_IMAGE_TAG)
        String registryProtocol = cfg.getMapValue(MAP_KEY_DOCKER_REGISTRY_PROTOCOL)
        String registryEndpoint = cfg.getMapValue(MAP_KEY_DOCKER_REGISTRY_ENDPOINT)
        String registryRegion = cfg.getMapValue(MAP_KEY_DOCKER_REGISTRY_REGION)
        String accountId = cfg.getMapValue(MAP_KEY_DOCKER_REGISTRY_ACCOUNT_ID)

        DockerUtils dockerUtils = new DockerUtils(dsl)
        dockerUtils.pullImage(registryEndpoint, accountId, registryRegion, imageTag)
        dockerUtils.tagImage(registryEndpoint, imageTag, imageName, imageTag)
        utils.checkDiskSpace()
    }

    /////////////////// result message management ///////////////////

    protected String getSlackMessageFirstPart(String buildResult) {
        String slackMessage = ""
        Configuration cfg = Configuration.getInstance()

        if (!"SUCCESS".equals(buildResult.toUpperCase())) {
            slackMessage = Utils.getNonSuccessStageResults(dsl.currentBuild) + "\n"
            if (cfg.getMapValue(MAP_KEY_UNSTABLE_MESSAGE) != null && cfg.getMapValue(MAP_KEY_UNSTABLE_MESSAGE)?.trim()) {
                slackMessage += "${cfg.getMapValue(MAP_KEY_UNSTABLE_MESSAGE)}\n"
            }
        }

        if (cfg.getMapValue(MAP_KEY_SLACK_MESSAGE) != null && cfg.getMapValue(MAP_KEY_SLACK_MESSAGE)?.trim()) {
            slackMessage += "${cfg.getMapValue(MAP_KEY_SLACK_MESSAGE)}\n"
        }

        return slackMessage
    }

    protected String getSlackMessageFinalPart() {
        String slackMessage = ""

        Configuration cfg = Configuration.getInstance()

        if (cfg.getMapValue(MAP_KEY_BLUE_OCEAN_URL) != null && cfg.getMapValue(MAP_KEY_BLUE_OCEAN_URL)?.trim()) {
            slackMessage = "<${cfg.getMapValue(MAP_KEY_BLUE_OCEAN_URL)}|${MESSAGE_OPEN_BLUE_OCEAN}>\n"
        }

        return slackMessage
    }

    protected String getSlackMessage(String emojCode, String buildResult) {
        String slackMessage = ":${emojCode}: ${buildResult}\n"

        slackMessage += this.getSlackMessageFirstPart(buildResult)
        slackMessage += this.getSlackMessageFinalPart()

        return slackMessage.trim()
    }

    String getSuccessSlackMessage() {
        return getSlackMessage("tada", "SUCCESS")
    }

    String getUnstableSlackMessage() {
        return getSlackMessage("warning", "UNSTABLE")
    }

    String getFailureSlackMessage() {
        return getSlackMessage("boom", "FAILURE")
    }

    String getAbortSlackMessage() {
        return getSlackMessage("octagonal_sign", "ABORTED")
    }

}
