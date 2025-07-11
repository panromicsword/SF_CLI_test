#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.process

import groovy.json.JsonOutput
import it.sky.dp.jenkins.nexus.NexusUploader
import it.sky.dp.jenkins.salesforce.Constants
import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.technology.*
import it.sky.dp.jenkins.salesforce.common.Utils

import static it.sky.dp.jenkins.salesforce.Constants.*

abstract class AbstractSalesforceProcess extends AbstractProcess implements Serializable {
    protected SalesforceUtils slfcUtils
    protected VlocityUtils vlocityUtils
    protected ManualProceduresUtils mpUtils
    protected Utils utils

    AbstractSalesforceProcess(def dsl) {
        super(dsl)
        this.slfcUtils = new SalesforceUtils(dsl)
        this.vlocityUtils = new VlocityUtils(dsl)
        this.mpUtils = new ManualProceduresUtils(dsl)
        this.utils = new Utils(dsl)
    }

    @Override
    void prepareBuildDescription() {
        Configuration cfg = Configuration.getInstance()
        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)
        String testLevel = cfg.getMapValue(MAP_KEY_TEST_LEVEL)

        buildDescr.add("Target Env: ${targetEnv}")
        buildDescr.add("Test Level: ${testLevel}")
    }

    @Override
    void initProcessVariables() {
        super.initProcessVariables()

        Configuration cfg = Configuration.getInstance()

        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)
        if (SLFC_ENV_NONE.equals(targetEnv)) {
            dsl.error("TARGET_ENVIRONMENT \"${targetEnv}\" not allowed. Please select a valid environment")
        }

        String jobName = cfg.getMapValue(MAP_KEY_JOB_NAME)
        if (jobName.endsWith(JOB_VENDOR_VALIDATE_SUFFIX) && !cfg.getMapValue(MAP_KEY_VALIDATE_ONLY)) {
            dsl.error("Job ${jobName} can't be executed with ${MAP_KEY_VALIDATE_ONLY} parameter unchecked")
        }

        String testLevel = cfg.getMapValue(MAP_KEY_TEST_LEVEL)
        cfg.addEntryToMap(MAP_KEY_TEST_LEVEL_VALIDATE, testLevel, true)

        if (SLFC_ENV_PRODUCTION.equals(targetEnv)) {
            cfg.addEntryToMap(MAP_KEY_SLFC_URL, SLFC_LOGIN_URL_PRODUCTION, true)

            if (TEST_RUN_NONE.equals(testLevel)) {
                dsl.error("Test cases must be executed on Production, please set a different test level rather then " + TEST_RUN_NONE)
            } else {
                cfg.addEntryToMap(MAP_KEY_TEST_LEVEL_DEPLOY, testLevel, true)
            }
        } else {
            cfg.addEntryToMap(MAP_KEY_SLFC_URL, SLFC_LOGIN_URL_TEST, true)
            cfg.addEntryToMap(MAP_KEY_TEST_LEVEL_DEPLOY, TEST_RUN_NONE, true)
        }

        boolean isQuickDeploy = (cfg.getMapValue(MAP_KEY_QUICK_DEPLOY_ID)?.trim()) ? true : false
        cfg.addEntryToMap(MAP_KEY_IS_QUICK_DEPLOY, isQuickDeploy, true)

        if (cfg.getMapValue(MAP_KEY_VALIDATE_ONLY) && isQuickDeploy) {
            dsl.error("Quick deploy ID cannot be passed in case of \"VALIDATE_ONLY\" parameter is checked")
        }
    }

    // calculate tag and hashes
    String getStartHash(AbstractSFUtils sfUtils, String mapKeyGitLastTag, String mapKeyGitSha1From) {
        Configuration cfg = Configuration.getInstance()
        String startHash = cfg.getMapValue(MAP_KEY_START_HASH)
        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)

        if (!startHash?.trim()) {
            String gitTagPattern = sfUtils.getTagPattern(targetEnv)
            String lastTag = gitUtils.getLastTagMatch(gitTagPattern)
            assert lastTag != null && lastTag != ""
            cfg.addEntryToMap(mapKeyGitLastTag, lastTag, true)
            startHash = gitUtils.getHashOfTag(lastTag)
            assert startHash != null && startHash != ""
        } else {
            cfg.addEntryToMap(mapKeyGitLastTag, "", false)
            dsl.echo("Git start hash calculation skipped due to value already set")
        }
        cfg.addEntryToMap(mapKeyGitSha1From, startHash, true)

        return startHash
    }

    void preDeploySalesforce() {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String version = cfg.getMapValue(MAP_KEY_RELEASE_VERSION)
        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)
        String endHash = cfg.getMapValue(MAP_KEY_GIT_SHA1_TO)

        String projectName = cfg.getMapValue(MAP_KEY_PROJECT_NAME)
        assert (projectName)

        String credentialId
        //if (DEFAULT_PROJECT.equals(projectName)) {
            credentialId = "Salesforce_Jenkins_${targetEnv.toUpperCase()}_ID"
        /*} else {
            credentialId = "Salesforce_Jenkins_${projectName.toUpperCase()}_${targetEnv.toUpperCase()}_ID"
        }*/
        cfg.addEntryToMap(MAP_KEY_SF_CREDENTIALS_ID, credentialId, true)

        if (!cfg.getMapValue(MAP_KEY_IS_QUICK_DEPLOY)) {
            slfcUtils.checkMetadataOk(workingPath)
            slfcUtils.checkReleaseFolderOk(workingPath, version)

            // calculate tag and hashes
            String startHash = this.getStartHash(slfcUtils, MAP_KEY_GIT_LAST_TAG, MAP_KEY_GIT_SHA1_FROM)

            slfcUtils.createDeltaFolder(workingPath)

            slfcUtils.copyResources(workingPath, startHash, endHash, version)
            slfcUtils.placeholdersReplace(workingPath, targetEnv)

            boolean deltaForceAppExists = slfcUtils.checkDeltaOk("${workingPath}/delta", "force-app", "Salesforce", false)

            String testLevel = cfg.getMapValue(MAP_KEY_TEST_LEVEL)
            if (deltaForceAppExists && testLevel.equals(TEST_RUN_SPECIFIC)) {
                // create "/delta/force-app/main/default/classes" if not exists
                dsl.sh "mkdir -p ${workingPath}/delta/force-app/main/default/classes"

                def testClassesList = slfcUtils.collectTestClasses(workingPath)
                def actualDefaultTest = null

                // the prod environment must have a default test
                if (testClassesList.size() == 0) {
                    if (SLFC_ENV_PRODUCTION.equals(targetEnv)) {
                        def testJsonConfig = dsl.readJSON file: "${workingPath}/devops/tests-catalog.json"
                        // json config management
                        def defaultTest = testJsonConfig."default"
                        dsl.echo "Default test-> ${defaultTest}"
                        if (defaultTest && !defaultTest.trim().equals("")) {
                            dsl.echo("WARNING: Adding default test for production \"${defaultTest}\"")
                            testClassesList.add("${defaultTest}.cls")
                            actualDefaultTest = defaultTest
                        } else {
                            dsl.error("No default test for production")
                        }
                    } else {
                        dsl.echo("WARNING: No test class tu run")
                        cfg.addEntryToMap(MAP_KEY_TEST_LEVEL_VALIDATE, TEST_RUN_NONE, true)
                    }
                }

                cfg.addEntryToMap(MAP_KEY_TEST_CLASSES, testClassesList, true)

                // copy test classes in delta
                testClassesList.each { testCls ->
                    dsl.sh("cp -f ${workingPath}/force-app/main/default/classes/${testCls} ${workingPath}/delta/force-app/main/default/classes/")
                    dsl.sh("cp -f ${workingPath}/force-app/main/default/classes/${testCls}-meta.xml ${workingPath}/delta/force-app/main/default/classes/")
                    assert (dsl.fileExists("${workingPath}/delta/force-app/main/default/classes/${testCls}"))
                }
                if (actualDefaultTest) {
                    dsl.sh("cp -f ${workingPath}/force-app/main/default/classes/${actualDefaultTest}.cls ${workingPath}/delta/force-app/main/default/classes/")
                    dsl.sh("cp -f ${workingPath}/force-app/main/default/classes/${actualDefaultTest}.cls-meta.xml ${workingPath}/delta/force-app/main/default/classes/")
                    assert (dsl.fileExists("${workingPath}/delta/force-app/main/default/classes/${actualDefaultTest}.cls"))
                }
            }
            slfcUtils.checkDeltaOk("${workingPath}/delta", "force-app", "Salesforce", true)
            boolean deltaClassesExists = slfcUtils.checkDeltaOk("${workingPath}/delta", "force-app/main/default/classes", "", false)
            boolean manualProceduresExists = slfcUtils.checkDeltaOk("${workingPath}/delta", "manual_procedures/${version}", "Manual Procedures", true)

            cfg.addEntryToMap(MAP_KEY_DELTA_FORCEAPP_EXISTS, deltaForceAppExists, false)
            cfg.addEntryToMap(MAP_KEY_DELTA_CLASSES_EXISTS, deltaClassesExists, false)
            cfg.addEntryToMap(MAP_KEY_DELTA_MANUAL_PROCEDURES_EXISTS, manualProceduresExists, false)

            // check source warnings and errors
            String warnings = slfcUtils.getSourceAlert("${workingPath}/delta", "force-app", cfg.getMapValue(MAP_KEY_SOURCE_ALERT_WARNING))
            if (warnings) {
                dsl.echo("WARNINGS :\n" + warnings)
            }
            String errors = slfcUtils.getSourceAlert("${workingPath}/delta", "force-app", cfg.getMapValue(MAP_KEY_SOURCE_ALERT_ERROR))
            if (errors) {
                dsl.error(errors)
            }

            boolean deltaManualProceduresAppExists = cfg.getMapValue(MAP_KEY_DELTA_MANUAL_PROCEDURES_EXISTS)
            if (deltaManualProceduresAppExists) {
                def slfcConfigs = cfg.getMapValue(MAP_KEY_SF_CONFIGS)
                def prodEnvironments = cfg.getMapValue(MAP_KEY_PROD_ENVIRONMENTS)
                def testEnvironments = cfg.getMapValue(MAP_KEY_TEST_ENVIRONMENTS)
                def envs = testEnvironments + prodEnvironments
                def vendors = slfcConfigs.vendors

                Map<String, ArrayList<ArrayList<String>>> mpMap = slfcUtils.getDeltaManualProceduresMap(workingPath, version, vendors, envs)
                slfcUtils.validateManualProcedures(workingPath, version, mpMap, targetEnv)
                cfg.addEntryToMap(MAP_KEY_MANUAL_PROCEDURES, mpMap, true)

                ArrayList<ArrayList<String>> prePhaseRecords = slfcUtils.getManualProceduresRecord(mpMap, STAGE_PRE, targetEnv)
                cfg.addEntryToMap(MAP_KEY_DELTA_PRE_MANUAL_PROCEDURES_EXISTS, (prePhaseRecords.size() > 0), false)
                cfg.addEntryToMap(MAP_KEY_PRE_MANUAL_PROCEDURES_LIST, prePhaseRecords, true)

                ArrayList<ArrayList<String>> postPhaseRecords = slfcUtils.getManualProceduresRecord(mpMap, STAGE_POST, targetEnv)
                cfg.addEntryToMap(MAP_KEY_DELTA_POST_MANUAL_PROCEDURES_EXISTS, (postPhaseRecords.size() > 0), false)
                cfg.addEntryToMap(MAP_KEY_POST_MANUAL_PROCEDURES_LIST, postPhaseRecords, true)
            }
        }

        String tagName = slfcUtils.getTagValue(targetEnv)
        cfg.addEntryToMap(MAP_KEY_DEPLOY_TAG_NAME, tagName, true)
    }

    protected String getVendorNameFromMPCsvFileName(String csvFileName) {
        def tokens = csvFileName.split("_")

        String vendorName = null
        if (tokens.size() > 3) {
            vendorName = (tokens[tokens.size() - 1]).toUpperCase().replace(".CSV", "")
        }

        return vendorName
    }

    protected void writeManualProceduresJson(String path) {
        Configuration cfg = Configuration.getInstance()
        Map<String, ArrayList<ArrayList<String>>> mpMap = cfg.getMapValue(MAP_KEY_MANUAL_PROCEDURES)

        if (!mpMap) { 
            mpMap = new HashMap<>()
        }
        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)
        def prodEnvironments = cfg.getMapValue(MAP_KEY_PROD_ENVIRONMENTS)
        def testEnvironments = cfg.getMapValue(MAP_KEY_TEST_ENVIRONMENTS)
        def envs = testEnvironments + prodEnvironments

        CsvSalesforceUtils csvUtil = new CsvSalesforceUtils(dsl)
        def csvHeaders = csvUtil.initHeader(envs)
        csvUtil.setUpHeader(csvHeaders)
        def manualProcedurestMap = [:]

        int mpToExecutePre = 0
        int mpToExecutePost = 0
        int mpPreToExecuteDuration = 0
        int mpPostToExecuteDuration = 0

        def vendorList = []

        mpMap.each { mpFile ->
            String mpCsvFilename = mpFile.key
            
            dsl.echo("working on " + mpCsvFilename)

            def vendorMap = [:]
            String vendorName = getVendorNameFromMPCsvFileName(mpCsvFilename)
            def vendorMpList = []


            mpFile.value.each { mpFileLine ->
                if (!mpFileLine.toString().startsWith("[ID") ) {
                    dsl.echo("mpFileLine  " + mpFileLine)
                    def mpItem = [:]

                    String recPhase = csvUtil.getRecordPhase(mpFileLine)
                    boolean toExecute = csvUtil.getHasToBeExecutedInEnv(mpFileLine, targetEnv)
                    boolean toExecuteInProd = csvUtil.getHasToBeExecutedInEnv(mpFileLine, SLFC_ENV_PRODUCTION)

                    // get duration int
                    int mpDuration = 0
                    String duration = csvUtil.getRecordDuration(mpFileLine)
                    if (duration && !duration.trim().equals("") && duration.trim() ==~ /[0-9]+/) {
                        mpDuration = duration.toInteger()
                    }

                    if (toExecuteInProd) {
                        if (STAGE_PRE.equals(recPhase.toUpperCase())) {
                            mpToExecutePre += 1
                            mpPreToExecuteDuration += mpDuration
                        } else {
                            mpToExecutePost += 1
                            mpPostToExecuteDuration += mpDuration
                        }
                    }

                    csvHeaders.each { headerItem ->
                        try {
                            Integer headerItemIdx = csvUtil.getHeaderIdx(headerItem)

                            mpItem[headerItem] = mpFileLine.get(headerItemIdx)
                            if ("DURATION".equals(headerItem)) {
                                mpItem[headerItem] = mpDuration
                            }
                        } catch (Exception e) {
                            dsl.echo(e.getMessage() + " - using default value for ${headerItem}.\n ${e.getStackTrace()}")
                            mpItem[headerItem] = ""
                        }
                    }
                    mpItem["TO_BE_EXECUTED"] = toExecute

                    vendorMpList.add(mpItem)

                }
            }
            vendorMap["name"] = vendorName
            vendorMap["manualProcedures"] = vendorMpList

            vendorList.add(vendorMap)
        }

        // summary
        def mpSummary = [:]

        mpSummary["preToExecute"] = mpToExecutePre
        mpSummary["postToExecute"] = mpToExecutePost
        mpSummary["toExecute"] = mpToExecutePre + mpToExecutePost
        mpSummary["mpPreToExecuteDuration"] = mpPreToExecuteDuration
        mpSummary["mpPostToExecuteDuration"] = mpPostToExecuteDuration
        mpSummary["durationToExecute"] = mpPreToExecuteDuration + mpPostToExecuteDuration

        manualProcedurestMap["manualProcedures"] = mpSummary

        // vendors
        manualProcedurestMap["vendors"] = vendorList

        dsl.writeFile(file: "${path}/manual_procedures.json", text: JsonOutput.toJson(manualProcedurestMap))
    }

    void preDeployVlocity() {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)
        String version = cfg.getMapValue(MAP_KEY_RELEASE_VERSION)

        String endHash = cfg.getMapValue(MAP_KEY_GIT_SHA1_TO)

        // calculate tag and hashes
        String startHash = this.getStartHash(vlocityUtils, MAP_KEY_VLOCITY_GIT_LAST_TAG, MAP_KEY_VLOCITY_GIT_SHA1_FROM)

        String tagNameVlocity = vlocityUtils.getTagValue(targetEnv)
        cfg.addEntryToMap(MAP_KEY_VLOCITY_DEPLOY_TAG_NAME, tagNameVlocity, true)

        vlocityUtils.createDeltaFolder(workingPath)

        vlocityUtils.copyResources(workingPath, startHash, endHash, version)

        boolean vlocityDiffExists = vlocityUtils.checkDeltaOk("${workingPath}/delta", "vlocity_components", "Vlocity", true)
        cfg.addEntryToMap(MAP_KEY_VLOCITY_DELTA_EXISTS, vlocityDiffExists, false)
    }

    void preDeploy() {
        // load configs file and initializations
        super.loadConfigs()

        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String endHash = cfg.getMapValue(MAP_KEY_END_HASH)
        Map inputParamsMap = cfg.getMapValue(MAP_KEY_JOB_INPUT_PARAMS)
        boolean validateOnly = cfg.getMapValue(MAP_KEY_VALIDATE_ONLY)
        boolean vlocityEnabled = cfg.getMapValue(MAP_KEY_VLOCITY_ENABLED)
        boolean skipInfoCommit = cfg.getMapValue(MAP_KEY_SKIP_INFO_COMMIT)

        if (!endHash?.trim()) {
            endHash = gitUtils.getHashOfTag("HEAD")
            assert endHash != null && endHash != ""
        } else {
            dsl.echo("Git end hash calculation skipped due to value already set")
        }
        cfg.addEntryToMap(MAP_KEY_GIT_SHA1_TO, endHash, true)

        this.preDeploySalesforce()

        if (vlocityEnabled) {
            this.preDeployVlocity()
        } else {
            cfg.addEntryToMap(MAP_KEY_VLOCITY_DELTA_EXISTS, false, false)
        }

        if (inputParamsMap) {
            def inputParamsJson = JsonOutput.toJson(inputParamsMap)
            dsl.writeJSON file: 'inputParams.json', json: inputParamsJson, pretty: 4
        }

        this.writeManualProceduresJson(workingPath)

        boolean deltaForceAppExists = cfg.getMapValue(MAP_KEY_DELTA_FORCEAPP_EXISTS)
        boolean deltaManualProceduresAppExists = cfg.getMapValue(MAP_KEY_DELTA_MANUAL_PROCEDURES_EXISTS)
        boolean deltaVlocityExists = cfg.getMapValue(MAP_KEY_VLOCITY_DELTA_EXISTS)

        boolean isQuickDeploy = cfg.getMapValue(MAP_KEY_IS_QUICK_DEPLOY)

        if (!(deltaForceAppExists || deltaManualProceduresAppExists || deltaVlocityExists)) {
            if (isQuickDeploy) {
                dsl.echo("No differences found during incremental calculation")
            } else {
                setUnstable("No differences found during incremental calculation")
            }
        } else {
            // unstable if only deltaVlocityExists and validate only
            if (validateOnly && !deltaForceAppExists) {
                setUnstable("No Salesforce differences to validate")
            } else {
                if (!skipInfoCommit) {
                    try {
                        this.writeInfoCommit()
                    } catch (Exception e) {
                        dsl.echo("An error occurred while getting the commit information.")
                    }
                }
            }
        }
        if (deltaVlocityExists) {
            try {
                this.writeVlocityComponentsJson(workingPath)
            } catch (Exception e) {
                dsl.echo("An error occurred while getting the vlocity components for report.")
            }
        }
        utils.checkDiskSpace()
    }

    // NEXUS STORE METHODS /////////////////////////////////////////////////////

    void createManifest(String path) {
        Configuration cfg = Configuration.getInstance()
        boolean vlocityEnabled = cfg.getMapValue(MAP_KEY_VLOCITY_ENABLED)

        String content = """${MAP_KEY_BRANCH_NAME}=${cfg.getMapValue(MAP_KEY_BRANCH_NAME)}
SLFC_START_TAG=${cfg.getMapValue(MAP_KEY_GIT_LAST_TAG)}
SLFC_START_HASH=${cfg.getMapValue(MAP_KEY_GIT_SHA1_FROM)}"""
        if (vlocityEnabled) {
            content += """VLOCITY_GIT_LAST_TAG=${cfg.getMapValue(MAP_KEY_VLOCITY_GIT_LAST_TAG)}
VLOCITY_START_HASH=${cfg.getMapValue(MAP_KEY_GIT_SHA1_FROM)}"""
        }
        content += """END_HASH=${cfg.getMapValue(MAP_KEY_GIT_SHA1_TO)}
TARGET_ENVIRONMENT=${cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)}
${MAP_KEY_TEST_LEVEL}=${cfg.getMapValue(MAP_KEY_TEST_LEVEL)}
"""

        dsl.writeFile(file: "${path}/MANIFEST.MF", text: content)
        dsl.sh """
            cd ${path}
            mkdir store
            cp -a ${path}/MANIFEST.MF ${path}/store/MANIFEST.MF
        """
    }

    void createStoreFile(String path) {
        Configuration cfg = Configuration.getInstance()
        boolean deltaForceAppExists = cfg.getMapValue(MAP_KEY_DELTA_FORCEAPP_EXISTS)
        boolean deltaManualProceduresAppExists = cfg.getMapValue(MAP_KEY_DELTA_MANUAL_PROCEDURES_EXISTS)
        boolean deltaVlocityExists = cfg.getMapValue(MAP_KEY_VLOCITY_DELTA_EXISTS)
        def prodEnvironments = cfg.getMapValue(MAP_KEY_PROD_ENVIRONMENTS)
        def testEnvironments = cfg.getMapValue(MAP_KEY_TEST_ENVIRONMENTS)
        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)

        def envs = testEnvironments + prodEnvironments

        this.createManifest(path)

        if (deltaForceAppExists) {
            slfcUtils.prepareStoreFile(path)
        }

        if (deltaManualProceduresAppExists) {
            Map<String, ArrayList<ArrayList<String>>> mpMap = cfg.getMapValue(MAP_KEY_MANUAL_PROCEDURES) 
            String releaseVersion = cfg.getMapValue(MAP_KEY_RELEASE_VERSION)
            mpUtils.prepareStoreFile(path, releaseVersion, mpMap, envs, targetEnv)
        }

        if (deltaVlocityExists) {
            vlocityUtils.prepareStoreFile(path)
        }

        dsl.zip dir: "${path}/store", glob: '', zipFile: "${path}/store.zip"
    }

    void convertSources() {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String version = cfg.getMapValue(MAP_KEY_RELEASE_VERSION)
        String slfcUrl = cfg.getMapValue(MAP_KEY_SLFC_URL)
        String salesforceVersion = "" + cfg.getMapValue(MAP_KEY_SALESFORCE_VERSION)

        slfcUtils.convertSources(workingPath, slfcUrl, salesforceVersion)
        slfcUtils.addCustomLabels(workingPath, slfcUrl, salesforceVersion)
        slfcUtils.checkForExistingPackage(workingPath, version)
    }

    void storeArtifact() {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)
        String branchName = cfg.getMapValue(MAP_KEY_BRANCH_NAME)
        String sourceBranchName = cfg.getMapValue(MAP_KEY_SOURCE_BRANCH_NAME)
        String tagName = cfg.getMapValue(MAP_KEY_DEPLOY_TAG_NAME)
        String tagNameVlocity = cfg.getMapValue(MAP_KEY_VLOCITY_DEPLOY_TAG_NAME)

        boolean deltaForceAppExists = cfg.getMapValue(MAP_KEY_DELTA_FORCEAPP_EXISTS)
        boolean deltaManualProceduresAppExists = cfg.getMapValue(MAP_KEY_DELTA_MANUAL_PROCEDURES_EXISTS)
        boolean vlocityEnabled = cfg.getMapValue(MAP_KEY_VLOCITY_ENABLED)
        boolean deltaVlocityExists = cfg.getMapValue(MAP_KEY_VLOCITY_DELTA_EXISTS)

        def slfcConfigs = cfg.getMapValue(MAP_KEY_SF_CONFIGS)

        this.createStoreFile(workingPath)

        String finalBranchName = (sourceBranchName?.trim()) ? sourceBranchName : branchName

        String finalTagName = tagName?.trim()
        if (!(deltaForceAppExists || deltaManualProceduresAppExists)) {
            assert (vlocityEnabled)
            assert (deltaVlocityExists)
            finalTagName = tagNameVlocity?.trim()
        }

        String nexusArtifactVersion = finalBranchName.toLowerCase() + "-" + finalTagName.toLowerCase()

        String classifier = ""
        String artifactSuffix = ""
        String artifactExtension = "zip"
        if (cfg.getMapValue(MAP_KEY_VALIDATE_ONLY)) {
            classifier = "debug"
            artifactSuffix = "-${classifier}"
        }

        String nexusProtocol = slfcConfigs.tools.nexus.protocol
        String nexusUrl = slfcConfigs.tools.nexus.url
        String nexusUrlZip = slfcConfigs.tools.nexus.repository.zipurl
        String nexusRepoName = slfcConfigs.tools.nexus.repository.name
        String nexusGroupId = slfcConfigs.tools.nexus.repository.groupId + "." + finalBranchName.toLowerCase() + "." + targetEnv.toLowerCase()
        String nexusGroupIdSlash = nexusGroupId.replace(".", "/")
        String nexusArtifactId = slfcConfigs.tools.nexus.repository.artifactId
        String nexusCredentialId = slfcConfigs.tools.nexus.credentialId

        NexusUploader nexusUploader = new NexusUploader(this.dsl, nexusUrl, nexusRepoName, nexusCredentialId)
        nexusUploader.uploadArtifact(nexusGroupId, nexusArtifactId, nexusArtifactVersion, classifier, "store.zip", artifactExtension)

        String nexusArtifactUrl = "${nexusProtocol}://${nexusUrlZip}/repository/${nexusRepoName}/${nexusGroupIdSlash}/${nexusArtifactId}/${nexusArtifactVersion}/${nexusArtifactId}-${nexusArtifactVersion}${artifactSuffix}.zip"
        dsl.echo("Nexus artifact url: ${nexusArtifactUrl}")

        cfg.addEntryToMap(MAP_KEY_NEXUS_ARTIFACT_URL, nexusArtifactUrl, true)
    }

    void preMPCheck() {
        Configuration cfg = Configuration.getInstance()
        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)

        Map<String, ArrayList<ArrayList<String>>> mpMap = cfg.getMapValue(MAP_KEY_MANUAL_PROCEDURES) 

        String mpString = slfcUtils.getManualProceduresToBeExecutedStr(mpMap, STAGE_PRE, targetEnv)
        dsl.echo(mpString)

        def approver = dsl.input(submitterParameter: 'approver',
                message: "Please perform all manual procedures listed in the log for the step PRE ${targetEnv}",
                ok: "All manual procedures performed"
        )
        cfg.addEntryToMap(MAP_KEY_MANUAL_PROCEDURES_PRE_APPROVER, approver, true)
    }

    protected void doValidate(boolean doBackup) {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String slfcUrl = cfg.getMapValue(MAP_KEY_SLFC_URL)
        String credentialsId = cfg.getMapValue(MAP_KEY_SF_CREDENTIALS_ID)
        String testLevel = cfg.getMapValue(MAP_KEY_TEST_LEVEL_VALIDATE)
        def testClsList = cfg.getMapValue(MAP_KEY_TEST_CLASSES)
        String logFileSuffix = cfg.getMapValue(MAP_KEY_NUMBER_PR)

        slfcUtils.validate(workingPath, slfcUrl, credentialsId, testLevel, testClsList, logFileSuffix, doBackup)
    }

    void validate() {
        Configuration cfg = Configuration.getInstance()
        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)
        boolean doBackup

        if (SLFC_ENV_PRODUCTION.equals(targetEnv)) {
            doBackup = true
        } else {
            doBackup = !cfg.getMapValue(MAP_KEY_VALIDATE_ONLY)
        }

        // TODO uncomment the lines below for skipping backup for specific environments and project
        /*
        String projectName = cfg.getMapValue(MAP_KEY_PROJECT_NAME)
        assert (projectName)

        if (DEFAULT_PROJECT.equals(projectName)) {
            if ("IT".equals(targetEnv) || "AM".equals(targetEnv)) {
                doBackup = false
            }
        } else if ("ADV".equals(projectName)) {
            if ("MASTERDEV".equals(targetEnv)) {
                doBackup = false
            }
        }
        */

        doValidate(doBackup)
    }

    void writeHtmlCoverageReports() {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        HtmlReportBuilder htmlReportBuilder = new HtmlReportBuilder(dsl)
        String monitoringJsonFileName = "${workingPath}/monitoring.json"

        if (dsl.fileExists(monitoringJsonFileName)) {
            def records = dsl.readJSON file: monitoringJsonFileName

            if (records.testExecution && (records.testExecution.size() > 0)) {
                def testExecution = records.testExecution
                testExecution.each { testExecutionResults ->
                    String className = testExecutionResults.className

                    def redLines = testExecutionResults.locationsNotCovered
                    dsl.echo "red lines:" + redLines
                    def redLinesArray = []
                    if (redLines.contains(";")) {
                        redLinesArray = redLines.split(";")
                    } else {
                        redLinesArray.add(redLines)
                    }
                    def redLinesInt = redLinesArray?.collect { it.toInteger() }
                    dsl.echo "red lines int:" + redLinesInt

                    if (redLinesInt.size() > 0) {
                        if (dsl.fileExists("${workingPath}/delta/force-app/main/default/classes/${className}.cls")) {
                            String inputLines = dsl.readFile "${workingPath}/delta/force-app/main/default/classes/${className}.cls"
                            String fileName = "${className}_coverage"

                            htmlReportBuilder.generateHtml(inputLines, redLinesInt, fileName)

                            if (dsl.fileExists("${workingPath}/${fileName}.html")) {
                                dsl.sh """
                                    cd ${workingPath}
                                    mkdir -p CoverageReports
                                    cp -a ${workingPath}/${fileName}.html ./CoverageReports
                                """
                            }
                        }
                    }
                }
            }
        } else {
            dsl.echo("File \"${monitoringJsonFileName}\" not found, code coverage html reports not availables")
        }
    }

    void quality() {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String jobName = cfg.getMapValue(MAP_KEY_JOB_NAME)
        boolean skipSca = cfg.getMapValue(MAP_KEY_SKIP_SCA)
        boolean deltaClassesExists = cfg.getMapValue(MAP_KEY_DELTA_CLASSES_EXISTS)
        String salesforceVersion = "" + cfg.getMapValue(MAP_KEY_SALESFORCE_VERSION)
        String slfcUrl = cfg.getMapValue(MAP_KEY_SLFC_URL)
        String credentialsId = cfg.getMapValue(MAP_KEY_SF_CREDENTIALS_ID)
        boolean deltaForceAppExists = cfg.getMapValue(MAP_KEY_DELTA_FORCEAPP_EXISTS)

        try {
            if (!skipSca && deltaClassesExists) {
                String deltaClasses = "delta/force-app/main/default/classes"

                PMDUtils pmdUtils = new PMDUtils(this.dsl)
                pmdUtils.runStaticCodeAnalysis(workingPath, deltaClasses, "apex", "json")
            }

            String deployId = ""
            if (deltaForceAppExists) {
                deployId = slfcUtils.getDeployId(workingPath, "")
                cfg.addEntryToMap(MAP_KEY_ACTUAL_DEPLOY_ID, deployId, true)
            }

            String reportName = "sfdcReport_${jobName}_${dsl.env.BUILD_NUMBER}.xlsx"
            boolean reportPMDOk = (!skipSca && deltaClassesExists)

            slfcUtils.generateValidationReport(workingPath, deployId, reportName, reportPMDOk, salesforceVersion, slfcUrl, credentialsId)

            writeHtmlCoverageReports()
        } catch (Exception e) {
            dsl.echo("There was an issue during the report generation. Following the error: " + e.getMessage())
        }
    }

    void updateElasticSearch() {
        dsl.echo("START ELASTIC SEARCH ")
        final String warningNoMonitoring = "Unable to update monitoring data"

        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String targetEnv = cfg.getMapValue(MAP_KEY_TARGET_ENVIRONMENT)
        String jobName = cfg.getMapValue(MAP_KEY_JOB_NAME)
        def slfcConfigs = cfg.getMapValue(MAP_KEY_SF_CONFIGS)
        def prodEnvironments = cfg.getMapValue(MAP_KEY_PROD_ENVIRONMENTS)
        def testEnvironments = cfg.getMapValue(MAP_KEY_TEST_ENVIRONMENTS)

        testEnvironments = testEnvironments ? testEnvironments : []
        prodEnvironments = prodEnvironments ? prodEnvironments : []

        def envs = testEnvironments + prodEnvironments

        if (!slfcConfigs) {
            dsl.echo("WARNING: Config not present into current branch")
            return
        }
        if (!slfcConfigs.tools.elasticsearch) {
            dsl.echo("WARNING: Elasticsearch config not present into current branch")
            return
        }

        String reportName = "sfdcReport_${jobName}_${dsl.env.BUILD_NUMBER}.xlsx"


        if (SLFC_ENV_PRODUCTION.equals(targetEnv) || SLFC_ENV_PRODRYRUN.equals(targetEnv)) {
            if (dsl.fileExists("${workingPath}/${reportName}")) {
                String monitoringJsonFileName = "${workingPath}/monitoring.json"
                if (dsl.fileExists(monitoringJsonFileName)) {
                    String releaseVersion = cfg.getMapValue(MAP_KEY_RELEASE_VERSION)
                    String branchName = cfg.getMapValue(MAP_KEY_BRANCH_NAME)
                    String buildUserId = cfg.getMapValue(MAP_KEY_BUILD_USER_ID)

                    String elasticsearchProtocol = slfcConfigs.tools.elasticsearch.protocol
                    String elasticsearchEndpoint = slfcConfigs.tools.elasticsearch.url
                    String elasticsearchPort = slfcConfigs.tools.elasticsearch.port
                    String elasticsearchUrl = "${elasticsearchProtocol}://${elasticsearchEndpoint}:" + elasticsearchPort

                    ElasticsearchUtils elkUtil = new ElasticsearchUtils(dsl)
                    Map releaseData = [:]
                    releaseData[MAP_KEY_ELASTICSEARCH_URL] = elasticsearchUrl
                    releaseData[MAP_KEY_ELASTICSEARCH_MONITORING_JSON_FILE_NAME] = monitoringJsonFileName
                    releaseData[MAP_KEY_ELASTICSEARCH_BRANCH_NAME] = branchName
                    releaseData[MAP_KEY_ELASTICSEARCH_BUILD_USER_ID] = buildUserId
                    releaseData[MAP_KEY_ELASTICSEARCH_RELEASE_VERSION] = releaseVersion
                    releaseData[MAP_KEY_ELASTICSEARCH_TARGET_ENV] = targetEnv
                    releaseData[MAP_KEY_ELASTICSEARCH_JOB_NAME] = jobName
                    releaseData[MAP_KEY_ELASTICSEARCH_BUILD_NUMBER] = dsl.env.BUILD_NUMBER
                    releaseData[MAP_KEY_ELASTICSEARCH_INDEX_PREFIX] = slfcConfigs.tools.elasticsearch.indexPrefix
                    releaseData[MAP_KEY_ELASTICSEARCH_ENVIRONMENTS] = envs

                    elkUtil.bulkInsertReleaseData(releaseData)
                } else {
                    dsl.echo("WARNING: file monitoring.json not found. " + warningNoMonitoring)
                }
            } else {
                dsl.echo("WARNING: file ${reportName} not found. " + warningNoMonitoring)
            }
        }
        utils.checkDiskSpace()
    }

    Map getInfoCommitMap() {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String hashTo = cfg.getMapValue(MAP_KEY_GIT_SHA1_TO)
        String hashFrom = cfg.getMapValue(MAP_KEY_GIT_SHA1_FROM)
        String version = cfg.getMapValue(MAP_KEY_RELEASE_VERSION)

        Map mapInfoCommits = [:]

        def filepathList = gitUtils.getDiffFiles(hashFrom, hashTo, null)

        filepathList.each { filepath ->
            def infoRowsChangedAndAuthorsMap = [:]


            if (filepath.startsWith("force-app/main/default") ||
                    filepath.startsWith("force-app/main/env-dependent") ||
                    filepath ==~ /^manual_procedures\// + version + /\/metadata\/.*$/) {

                try {
                    // nuovi sviluppi
                    infoRowsChangedAndAuthorsMap = gitUtils.getRowsModifiedAndAuthors(hashTo, hashFrom, filepath, workingPath)
                } catch (Exception e) {
                    dsl.echo("Error during get author for file " + filepath)
                }

                if (infoRowsChangedAndAuthorsMap && infoRowsChangedAndAuthorsMap.size() > 0) {
                    mapInfoCommits[filepath] = infoRowsChangedAndAuthorsMap
                }
            }
        }
        dsl.echo("Map Info Commit:" + mapInfoCommits)

        return mapInfoCommits
    }

    String getFileName(String filePath) {
        Configuration cfg = Configuration.getInstance()
        String version = cfg.getMapValue(MAP_KEY_RELEASE_VERSION)

        def filePathArray = filePath.split("/")
        String fileType
        String fileName
        String fullFileName

        if (filePath.startsWith("force-app/main/default")) {
            fileType = filePathArray[3]
            fileName = filePathArray[4]

            if (fileName.endsWith("-meta.xml")) {
                fileName = fileName.replace("-meta.xml", "").trim()
            }

            switch (fileType) {
                case "staticresources":
                    def filenameData = fileName.split("\\.")
                    fileName = filenameData[0] + ".resource"
                    break
                case "objects":
                    fileName = fileName + "." + fileType.substring(0, fileType.size() - 1)
                    break
            }

            fullFileName = fileType + "/" + fileName
        } else if (filePath.startsWith("force-app/main/env-dependent") || filePath ==~ /^manual_procedures\// + version + /\/metadata\/.*$/) {
            fileType = filePathArray[3]

            switch (fileType) {
                case "objects":
                    fileName = filePathArray[4]
                    fileName = fileName + "." + fileType.substring(0, fileType.size() - 1)
                    break
                default:
                    fileType = filePathArray[filePathArray.size() - 2]
                    fileName = filePathArray[filePathArray.size() - 1]

                    if (fileName.endsWith("-meta.xml")) {
                        fileName = fileName.replace("-meta.xml", "").trim()
                    }
                    break
            }

            fullFileName = fileType + "/" + fileName
        } else {
            dsl.echo("No action needed for filepath: " + filePath)
        }

        return fullFileName
    }

    void writeInfoCommitJson(Map mapInfoCommits) {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)

        def infoCommitMapTmp = [:]

        String infoUsersFileStr = "[]"
        String infoUsersFilePath = "${workingPath}/devops/info-users.json"

        if (dsl.fileExists(infoUsersFilePath)) {
            infoUsersFileStr = dsl.readFile file: infoUsersFilePath
            dsl.echo("File \"${infoUsersFilePath}\" loaded successfully")
        } else {
            dsl.echo("File \"${infoUsersFilePath}\" doesn't exists, no vendor informations available")
        }

        def infoUsers = dsl.readJSON text: infoUsersFileStr
        dsl.echo("\n" + infoUsers + "\n")

        mapInfoCommits.each { infoCommit ->
            String filename = infoCommit.key
            String finalFilename = this.getFileName(filename)

            def owners = []
            if (infoCommit.value.size() > 0 && infoCommit.value.authors.size() > 0) {
                owners.addAll(infoCommit.value.authors)
            }

            def infoCommitItem
            def newOwners

            if (infoCommitMapTmp.containsKey(finalFilename)) {
                infoCommitItem = infoCommitMapTmp[finalFilename]
                dsl.echo("infoCommitItem - Element found->" + infoCommitItem)
                def oldOwners = infoCommitItem["owners"]
                newOwners = oldOwners + owners
                newOwners = newOwners.unique()
            } else {
                infoCommitItem = [:]
                infoCommitItem["filename"] = filename
                infoCommitItem["finalFilename"] = finalFilename
                newOwners = owners.unique()
            }

            // get vendor for each unique user
            def vendors = []

            newOwners.each { owner ->
                String vendor = infoUsers.find { it.usernames.find { it.equals(owner) } }?.vendor
                if (!vendor) {
                    vendor = "unknown"
                }
                vendors.add(vendor)
            }

            infoCommitItem["owners"] = newOwners
            infoCommitItem["vendors"] = vendors.unique()

            // aggiunta info su righe aggiunte e/o eliminate
            if (infoCommit.value.size() > 0 && infoCommit.value.deletion >= 0) {
                infoCommitItem["deletion"] = infoCommit.value.deletion
            }

            if (infoCommit.value.size() > 0 && infoCommit.value.insertion >= 0) {
                infoCommitItem["insertion"] = infoCommit.value.insertion
            }

            if (infoCommit.value.size() > 0 && infoCommit.value.creationDate?.trim()) {
                infoCommitItem["creationDate"] = infoCommit.value.creationDate
            }

            infoCommitMapTmp[finalFilename] = infoCommitItem
        }

        def infoCommitList = []

        infoCommitMapTmp.each {
            infoCommitList.add(it.value)
        }
        dsl.echo("infoCommitList-->" + infoCommitList)

        dsl.writeJSON file: "${workingPath}/info_commits.json", json: infoCommitList
    }

    void writeInfoCommit() {
        dsl.echo("------INFO COMMIT------\n")

        Map mapInfoCommits = this.getInfoCommitMap()
        if (!mapInfoCommits.isEmpty()) {
            this.writeInfoCommitJson(mapInfoCommits)
        }
    }

    void writeVlocityComponentsJson(String workingPath) {
        def vlocityComponentsList = vlocityUtils.getVlocityComponents("${workingPath}/delta")

        def vlocityComponentsInfo = [:]
        if (vlocityComponentsList) {
            vlocityComponentsList.each { component ->

                def componentList = component.split("/")
                String componentType = componentList[1]
                String componentName = componentList[2]

                def infoVlocityComponentsNameList = vlocityComponentsInfo[componentType]

                if (!infoVlocityComponentsNameList) {
                    infoVlocityComponentsNameList = []
                }

                if (!(componentName in infoVlocityComponentsNameList)) {
                    infoVlocityComponentsNameList.add(componentName)
                }
                vlocityComponentsInfo[componentType] = infoVlocityComponentsNameList
            }

            dsl.echo("vlocityComponentsInfo-->" + vlocityComponentsInfo)
            dsl.writeJSON file: "${workingPath}/vlocity_components.json", json: vlocityComponentsInfo
        }
    }
}
