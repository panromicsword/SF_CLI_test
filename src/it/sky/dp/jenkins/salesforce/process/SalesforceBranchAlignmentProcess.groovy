#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.process

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.scm.GithubUtils
import it.sky.dp.jenkins.salesforce.technology.CsvSalesforceUtils

import static it.sky.dp.jenkins.salesforce.Constants.*

class SalesforceBranchAlignmentProcess extends AbstractProcess implements Serializable {
    static final String NOT_AVAILABLE = "NA"
    static final String JOB_RESULT_FAILURE = "FAILURE"
    static final String JOB_RESULT_SUCCESS = "SUCCESS"
    final String COLUMN_ID = "ID"

    static final String MAP_KEY_ID = "ID"
    static final String MAP_KEY_SOURCE_BRANCH = "sourceBranch"
    static final String MAP_KEY_TARGET_BRANCH = "targetBranch"
    static final String MAP_KEY_TARGET_ENV = "targetEnvironment"
    static final String MAP_KEY_TEST_LEV = "testLevel"
    static final String MAP_KEY_RELEASE_VERSION_TARGET = "releaseVersionTarget"
    static final String MAP_KEY_RELEASE_VERSION_SOURCE = "releaseVersionSource"
    static final String MAP_KEY_CREATE_PR_JOB_RESULT = "createPrJobResult"
    static final String MAP_KEY_ACCEPT_PR_JOB_RESULT = "acceptPrJobResult"
    static final String MAP_KEY_DEPLOY_JOB_RESULT = "deployJobResult"

    static final String MAP_KEY_ACCEPT_PR = "acceptPR"
    static final String MAP_KEY_DEPLOY = "deploy"
    static final String MAP_KEY_DEPENDENCY = "dependency"

    static final String SOURCE_REPO_FOLDER = "sourceRepo"
    static final String TARGET_REPO_FOLDER = "targetRepo"
    static final String ALIGNMENT_FOLDER = "Alignment"

    protected GithubUtils githubUtils
    protected CsvSalesforceUtils csvUtils

    SalesforceBranchAlignmentProcess(def dsl) {
        super(dsl)
        this.githubUtils = new GithubUtils(dsl)
        this.csvUtils = new CsvSalesforceUtils(dsl)
    }

    @Override
    void checkParameters() {
        super.checkParam(MAP_KEY_BRANCH_CONFIGURATION)
    }

    @Override
    void initProcessVariables() {
        super.initProcessVariables()
        Configuration cfg = Configuration.getInstance()

        Map alignmentJobs = [:]
        def branchConfigurationJson = null
        try {
            String branchConfiguration = cfg.getMapValue(MAP_KEY_BRANCH_CONFIGURATION)
            branchConfigurationJson = dsl.readJSON text: branchConfiguration
        } catch (Exception e) {
            dsl.error("Error in Json: " + e.getMessage())
        }

        this.isBranchConfigValid(branchConfigurationJson)

        branchConfigurationJson.branches.each { branch ->
            Map branchMap = [:]
            String elementId = branch.id.toString()
            branchMap[MAP_KEY_ID] = elementId
            branchMap[MAP_KEY_SOURCE_BRANCH] = branch.sourceBranch
            branchMap[MAP_KEY_TARGET_BRANCH] = branch.targetBranch
            branchMap[MAP_KEY_ACCEPT_PR] = branch.acceptPR
            branchMap[MAP_KEY_DEPLOY] = branch.deploy
            branchMap[MAP_KEY_TARGET_ENV] = branch.targetEnvironment
            branchMap[MAP_KEY_TEST_LEV] = branch.testLevel
            branchMap[MAP_KEY_RELEASE_VERSION_SOURCE] = branch.releaseVersionSource
            branchMap[MAP_KEY_RELEASE_VERSION_TARGET] = branch.releaseVersionTarget
            def dependencyList = []
            branch.dependency.each {
                dependencyList.add(it)
            }
            branchMap[MAP_KEY_DEPENDENCY] = dependencyList
            branchMap[MAP_KEY_CREATE_PR_JOB_RESULT] = null
            branchMap[MAP_KEY_ACCEPT_PR_JOB_RESULT] = null
            branchMap[MAP_KEY_DEPLOY_JOB_RESULT] = null

            alignmentJobs[elementId] = branchMap
        }

        dsl.echo("Alignment to execute: " + alignmentJobs)
        cfg.addEntryToMap(MAP_KEY_ALIGNMENT_JOBS, alignmentJobs, true)
    }

    void isBranchConfigValid(def branchConfigurationJson) {
        def errorList = []

        if (branchConfigurationJson && branchConfigurationJson.branches) {
            branchConfigurationJson.branches.each { branch ->
                if (branch.acceptPR == false && branch.deploy == true) {
                    errorList.add("For the branch: \"${branch.targetBranch}\", the parameter acceptPR is: \"${branch.acceptPR}\" and the " +
                            "parameter deploy is: \"${branch.deploy}\"\n" + "Please, check the input parameter BRANCH_CONFIGURATION json")
                }
                if (!branch.sourceBranch?.trim()) {
                    errorList.add("Source Branch is NULL or EMPTY for target branch: \"${branch.targetBranch}\"")
                }
                if (!branch.targetBranch?.trim()) {
                    errorList.add("Target Branch is NULL or EMPTY ")
                }
                if (!branch.releaseVersionSource?.trim()) {
                    errorList.add("Release Version is NULL or EMPTY for source branch: \"${branch.sourceBranch}\"")
                }
                if (!branch.releaseVersionTarget?.trim()) {
                    errorList.add("Release Version is NULL or EMPTY for target branch: \"${branch.targetBranch}\"")
                }
                if (!branch.targetEnvironment?.trim()) {
                    errorList.add("Target Environment is NULL or EMPTY for target branch: \"${branch.targetBranch}\"")
                }
                if (!branch.testLevel?.trim()) {
                    errorList.add("Test Level is NULL or EMPTY for target branch: \"${branch.targetBranch}\"")
                }
                if (!(branch.testLevel in TEST_LEVELS)) {
                    errorList.add("Entered Test Level is wrong for target branch: \"${branch.targetBranch}\", please check")
                }
            }
        }

        if (errorList.size() > 0) {
            dsl.error("-------- ERROR LIST --------\n\n" + errorList.join("\n"))
        }
    }

    void alignBranches() {
        this.initFolders()
        this.scheduleJobs()
        this.printFinalJobResults()
    }

    void initFolders() {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)

        dsl.sh "mkdir -p ${SOURCE_REPO_FOLDER}"

        dsl.dir(SOURCE_REPO_FOLDER) {
            cfg.addEntryToMap(MAP_KEY_BRANCH_NAME, "master", true)
            checkoutSources()

            cfg.addEntryToMap(MAP_KEY_WORKING_PATH, ".", true)
            loadConfigs()
            cfg.addEntryToMap(MAP_KEY_WORKING_PATH, dsl.env.WORKSPACE, true)
            workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        }

        dsl.sh "cp -r ${workingPath}/${SOURCE_REPO_FOLDER} ${workingPath}/${TARGET_REPO_FOLDER}"
    }

    void scheduleJobs() {
        Configuration cfg = Configuration.getInstance()
        def alignmentJobs = cfg.getMapValue(MAP_KEY_ALIGNMENT_JOBS)

        int maxloop = 5
        int i = 1

        dsl.echo("-------- SCHEDULE JOB EXECUTION --------")

        while (i <= maxloop) {
            dsl.echo("-------- Starting iteration #" + i + " --------")

            alignmentJobs.each { alignmentJob ->
                boolean depOk = this.jobDependencyOk(alignmentJob.value)
                if (alignmentJob.value[MAP_KEY_CREATE_PR_JOB_RESULT] == null && depOk) {
                    this.createPullRequestJob(alignmentJob.value)
                }
            }

            Map prAcceptanceJobs = this.schedulePullRequestAcceptanceJob()
            dsl.parallel prAcceptanceJobs

            Map deployJobs = this.scheduleDeployJob()
            dsl.parallel deployJobs

            i++
        }

        dsl.echo("alignmentJobs-->" + alignmentJobs)
    }

    boolean jobDependencyOk(def alignmentJob) {
        boolean result = false
        Configuration cfg = Configuration.getInstance()
        def alignmentJobs = cfg.getMapValue(MAP_KEY_ALIGNMENT_JOBS)

        def dependecyIdArr = alignmentJob[MAP_KEY_DEPENDENCY]

        if (dependecyIdArr.size() == 0) {
            result = true
        } else {
            dependecyIdArr.each { dependencyId ->
                def upstreamJob = alignmentJobs[dependencyId.toString()]
                assert upstreamJob
                result = (upstreamJob[MAP_KEY_CREATE_PR_JOB_RESULT].equals(JOB_RESULT_SUCCESS) && upstreamJob[MAP_KEY_ACCEPT_PR_JOB_RESULT].equals(JOB_RESULT_SUCCESS))
            }
        }

        return result
    }

    boolean mergeLocal(String sourceBranch, String targetBranch, String releaseVersionSource, String releaseVersionTarget, String targetEnvironment) {
        Configuration cfg = Configuration.getInstance()
        String repositoryUrl = cfg.getMapValue(MAP_KEY_REPOSITORY_URL)
        String repositoryCredentialId = cfg.getMapValue(MAP_KEY_REPOSITORY_CREDENTIAL_ID)
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)

        boolean localmerged = false
        Map<String, ArrayList<ArrayList<String>>> manualProceduresCsvSource = new HashMap<>()
        Map<String, ArrayList<ArrayList<String>>> manualProceduresCsvTarget = new HashMap<>()
        Map<String, ArrayList<ArrayList<String>>> mergedManualProceduresCsv = new HashMap<>()

        boolean sourceBranchExists = false
        boolean sourceBranchFolderOk = false

        dsl.dir(SOURCE_REPO_FOLDER) {
            sourceBranchExists = gitUtils.checkBranchExist(sourceBranch, true)
            if (sourceBranchExists) {
                gitUtils.checkoutBranch(repositoryUrl, sourceBranch, repositoryCredentialId, workingPath)
                gitUtils.setRemoteUrl(repositoryUrl, repositoryCredentialId)
                gitUtils.pull(sourceBranch)
                if (!dsl.fileExists("manual_procedures/${releaseVersionSource}")) {
                    setUnstable("Release Version \"${releaseVersionSource}\" of source branch ${sourceBranch} doesn't exists")
                } else {
                    manualProceduresCsvSource = this.loadManualProceduresCsv(releaseVersionSource)
                    dsl.echo("manualProceduresCsvSource : " + manualProceduresCsvSource)
                    sourceBranchFolderOk = true
                }
            } else {
                setUnstable("${sourceBranch} doesn't exists")
            }
        }

        if (sourceBranchFolderOk) {
            dsl.dir(TARGET_REPO_FOLDER) {
                if (gitUtils.checkBranchExist(targetBranch, true)) {
                    gitUtils.checkoutBranch(repositoryUrl, targetBranch, repositoryCredentialId, workingPath)
                    gitUtils.setRemoteUrl(repositoryUrl, repositoryCredentialId)
                    gitUtils.pull(targetBranch)

                    if (!dsl.fileExists("manual_procedures/${releaseVersionTarget}")) {
                        setUnstable("Release Version \"${releaseVersionTarget}\" of target branch ${targetBranch} doesn't exists")
                    } else {
                        manualProceduresCsvTarget = this.loadManualProceduresCsv(releaseVersionTarget)
                        dsl.echo("manualProceduresCsvTarget : " + manualProceduresCsvTarget)
                        mergedManualProceduresCsv = this.getMergedManualProceduresCsv(manualProceduresCsvSource, manualProceduresCsvTarget, targetEnvironment)
                        dsl.echo("Merged manual procedures -->" + mergedManualProceduresCsv)

                        String localTemporaryBranch = "${sourceBranch}_${targetBranch}_alignment"
                        boolean remoteTemporaryBranchExists = gitUtils.checkBranchExist(localTemporaryBranch, true)

                        if (remoteTemporaryBranchExists) {
                            setUnstable("Remote ${localTemporaryBranch} already exists")
                        } else {
                            //creo il branch di appoggio
                            boolean localTemporaryBranchExists = gitUtils.checkBranchExist(localTemporaryBranch, false)
                            assert !localTemporaryBranchExists
                            gitUtils.checkoutLocalBranch(localTemporaryBranch, false, workingPath)
                            this.createFolderAlignment(releaseVersionTarget)

                            String commitMessage
                            try {
                                try {
                                    // local merge (source branch -> target branch)
                                    gitUtils.setConfigs()
                                    dsl.sh("git config --global  pull.ff true")
                                    gitUtils.mergeSources(sourceBranch)
                                    localmerged = true
                                } catch (Exception e) {
                                    setUnstable("Error merging locally")
                                    this.writeConflictCsvFile(releaseVersionTarget, releaseVersionSource)
                                }

                                String workingBranch
                                if (localmerged) {
                                    gitUtils.checkoutLocalBranch(targetBranch, true, workingPath)
                                    gitUtils.cleanBranch(targetBranch, true, workingPath)
                                    this.createFolderAlignment(releaseVersionTarget)
                                    workingBranch = targetBranch
                                    commitMessage = "Automatic alignment from ${sourceBranch}"
                                } else {
                                    workingBranch = localTemporaryBranch
                                    commitMessage = "Alignment branch \"${localTemporaryBranch}\" created"
                                }
                                //aggiunto metodo di copia dei metadata da source a target
                                this.copyMetadataFromSource(releaseVersionSource, releaseVersionTarget)
                                this.copyManualProceduresToTarget(releaseVersionSource, releaseVersionTarget)
                                this.writeMergedManualProceduresCsv(mergedManualProceduresCsv, releaseVersionTarget)
                                dsl.echo("Working branch --->" + workingBranch)
                                gitUtils.commitAndPushBranch(workingBranch, localmerged, commitMessage)
                            } finally {
                                gitUtils.cleanBranch(targetBranch, localmerged, workingPath)
                                if (!localmerged) {
                                    gitUtils.checkoutLocalBranch(targetBranch, true, workingPath)
                                    gitUtils.deleteLocalBranch(localTemporaryBranch)
                                }
                            }
                        }
                    }
                } else {
                    setUnstable("Remote ${targetBranch} doesn't exists")
                }
            }
        }

        return localmerged
    }

    def getUpdateSourceManualProcedure(ArrayList<ArrayList<String>> sourceVendorCsv, String targetEnvironment, int idCount, def devEnvironments) {
        def csvMPs = []

        sourceVendorCsv.each { mp ->
            String csvMP = ""

            if (!csvUtils.getRecordId(mp).toUpperCase().equals("ID")) {
                idCount++

                String id = (idCount > 0) ? idCount + "" : csvUtils.getRecordId(mp)
                csvMP += id
                csvMP += "," + csvUtils.getRecordPhase(mp)

                String environment = csvUtils.getRecordEnvironment(mp).toUpperCase()
                if ("ALL".equals(environment) || environment.contains(targetEnvironment)) {
                    environment = targetEnvironment
                } else if (!environment.contains(targetEnvironment)) {
                    environment = "ALIGNMENT"
                }
                csvMP += "," + environment

                csvMP += "," + csvUtils.getRecordFilename(mp)
                csvMP += "," + csvUtils.getRecordHasAttachment(mp).toString().toUpperCase()
                csvMP += "," + csvUtils.getRecordRequester(mp)
                csvMP += "," + csvUtils.getRecordDuration(mp).toString()

                devEnvironments.each { env ->
                    def envValue = csvUtils.getRecordExecutionEnv(mp, env)
                    envValue = envValue ? envValue : ""
                    csvMP += "," + envValue
                }

                // optional environment
                if (csvUtils.COLUMN_CM_ACNDEVOPS in csvUtils.getHeader()) {
                    csvMP += "," + csvUtils.getRecordExecutionEnv(mp, "ACNDEVOPS")
                }
                csvMPs.add(csvMP)
                //csvMPs.add(csvMP.split(",", -1))
            } else {
                csvMP += csvUtils.COLUMN_ID
                csvMP += "," + csvUtils.COLUMN_PHASE
                csvMP += "," + csvUtils.COLUMN_ENVIRONMENT
                csvMP += "," + csvUtils.COLUMN_FILENAME
                csvMP += "," + csvUtils.COLUMN_HAS_ATTACHMENTS
                csvMP += "," + csvUtils.COLUMN_REQUESTER
                csvMP += "," + csvUtils.COLUMN_DURATION

                devEnvironments.each { env ->
                    String envColumns = "CM_${env.toUpperCase()}"
                    csvMP += "," + envColumns
                }

                csvMPs.add(csvMP)
            }
        }
        csvMPs.join("\n")

        dsl.echo("csvMPs->" + csvMPs)
        return csvMPs
    }

    Map<String, ArrayList<ArrayList<String>>>  getMergedManualProceduresCsv(Map<String, ArrayList<ArrayList<String>>> mpSourceCsv,
                                                                            Map<String, ArrayList<ArrayList<String>>> mpTargetCsv,
                                                                            String targetEnvironment) {
        Map<String, ArrayList<ArrayList<String>>> mpFinalCsv = mpTargetCsv.clone()

        mpSourceCsv.each { mp ->
            String vendor = mp.getKey()
            ArrayList<ArrayList<String>> finalVendorCsv = mpFinalCsv.get(vendor)
            ArrayList<ArrayList<String>> sourceVendorCsv = mp.getValue()

            int idCount = 0
            if (finalVendorCsv) {
                finalVendorCsv.each { line ->
                    String idTarget = csvUtils.getRecordId(line)
                    int idTargetNum
                    try {
                        if (!idTarget.equals(COLUMN_ID)) {
                            idTargetNum = idTarget.toInteger()

                            if (idTargetNum > idCount) {
                                idCount = idTargetNum
                            }
                        }
                    } catch (Exception e) {
                        dsl.error("Error during the parse to int of ID " + idTarget + " about vendor CSV " + vendor)
                    }
                }
            }

            if (sourceVendorCsv) {
                Configuration cfg = Configuration.getInstance()
                def prodEnvironments = cfg.getMapValue(MAP_KEY_PROD_ENVIRONMENTS)
                def testEnvironments = cfg.getMapValue(MAP_KEY_TEST_ENVIRONMENTS)
                def envs = testEnvironments + prodEnvironments

                def mpSourceCsvNew = this.getUpdateSourceManualProcedure(sourceVendorCsv, targetEnvironment, idCount, envs)

                dsl.echo("mpSourceCsvNew : " + mpSourceCsvNew)

                String nameFile = "New_Manual_Procedures_Source_${vendor}.csv"
                csvUtils.writeAlignmentManualProcedureCsv(nameFile, mpSourceCsvNew, envs)
                def newSourceManualProcedures = csvUtils.read("New_Manual_Procedures_Source_${vendor}.csv", envs, false)
                //rimuovo i file csv non più necessari
                dsl.sh("rm -rf New_Manual_Procedures_Source_${vendor}.csv")
                // empty dest
                if (finalVendorCsv) {
                    newSourceManualProcedures.each { mpLine ->
                        boolean mpExist = this.manualProceduresExistInTargetCsv(mpLine, finalVendorCsv)
                        if (mpExist) {
                            String id = csvUtils.getRecordId(mpLine)
                            dsl.echo("Manual procedure with ID: " + id + " skipped because already exists")
                        } else {
                            finalVendorCsv.add(mpLine)
                        }
                    }
                } else {
                    finalVendorCsv = newSourceManualProcedures.clone()
                    mpFinalCsv[vendor] = finalVendorCsv
                }
            }
        }
        dsl.echo("mpFinalCsv->" + mpFinalCsv)
        return mpFinalCsv
    }

    void createFolderAlignment(String releaseVersionTarget) {
        dsl.dir("manual_procedures/${releaseVersionTarget}") {
            dsl.sh "mkdir -p ${ALIGNMENT_FOLDER}"
        }
    }

    void copyManualProceduresToTarget(String releaseVersionSource, String releaseVersionTarget) {
        Configuration cfg = Configuration.getInstance()
        def slfcConfigs = cfg.getMapValue(MAP_KEY_SF_CONFIGS)
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        def vendors = slfcConfigs.vendors
        vendors.each { vendor ->
            if (dsl.fileExists("${workingPath}/${SOURCE_REPO_FOLDER}/manual_procedures/${releaseVersionSource}/${vendor}")) {
                this.copyManualProcedureFromSource(workingPath, vendor, releaseVersionSource, releaseVersionTarget)
                if (dsl.fileExists("manual_procedures/${releaseVersionTarget}/${vendor}/mp_source")) {
                    dsl.sh("rm -rf manual_procedures/${releaseVersionTarget}/${vendor}/mp_source/${releaseVersionSource}_Manual_Procedures_${vendor}.csv; cp -Rp  manual_procedures/${releaseVersionTarget}/${vendor}/mp_source/. ./manual_procedures/${releaseVersionTarget}/${vendor} ; rm -rf manual_procedures/${releaseVersionTarget}/${vendor}/mp_source")
                }
            }
        }
    }

    void copyManualProcedureFromSource(String workingPath, String vendor, String releaseVersionSource, String releaseVersionTarget) {
        dsl.dir(workingPath) {
            if (dsl.fileExists("${TARGET_REPO_FOLDER}/manual_procedures/${releaseVersionTarget}/${vendor}")) {
                dsl.sh("cp -avr ${SOURCE_REPO_FOLDER}/manual_procedures/${releaseVersionSource}/${vendor} ${TARGET_REPO_FOLDER}/manual_procedures/${releaseVersionTarget}/${vendor}/mp_source")
            } else {
                dsl.dir("${TARGET_REPO_FOLDER}/manual_procedures/${releaseVersionTarget}") {
                    dsl.sh("mkdir -p ${vendor}")
                }
                dsl.sh("cp -avr ${SOURCE_REPO_FOLDER}/manual_procedures/${releaseVersionSource}/${vendor} ${TARGET_REPO_FOLDER}/manual_procedures/${releaseVersionTarget}/${vendor}/mp_source")
            }
        }
    }

    void copyMetadataFromSource(String releaseVersionSource, String releaseVersionTarget) {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        dsl.dir(workingPath) {
            if (dsl.fileExists("${TARGET_REPO_FOLDER}/manual_procedures/${releaseVersionTarget}/metadata")) {
                dsl.sh("cp -avr ${SOURCE_REPO_FOLDER}/manual_procedures/${releaseVersionSource}/metadata ${TARGET_REPO_FOLDER}/manual_procedures/${releaseVersionTarget}/${ALIGNMENT_FOLDER}/${releaseVersionSource}_#${dsl.BUILD_NUMBER}_metadata")
            }
        }
    }

    void writeMergedManualProceduresCsv(def mpCsvTargetFinal, String releaseVersioneTarget) {
        Configuration cfg = Configuration.getInstance()
        def slfcConfigs = cfg.getMapValue(MAP_KEY_SF_CONFIGS)
        def prodEnvironments = cfg.getMapValue(MAP_KEY_PROD_ENVIRONMENTS)
        def testEnvironments = cfg.getMapValue(MAP_KEY_TEST_ENVIRONMENTS)
        def envs = testEnvironments + prodEnvironments

        def vendors = slfcConfigs.vendors
        vendors.each { vend ->
            mpCsvTargetFinal.each {
                String vendor = it.key
                if (vendor.equals(vend)) {
                    def csvMPs = []
                    dsl.dir("manual_procedures/${releaseVersioneTarget}/${vendor}") {
                        String nameFileCSV = "${releaseVersioneTarget}_Manual_Procedures_${vendor}.csv"
                        it.value.each { mpline ->
                            String csvMP = ""
                            csvMP += mpline[0]
                            for (int i=1; i<mpline.size(); i++) {
                                csvMP += "," + mpline[i].trim()
                            }

                            //dsl.echo ("csvMP : " + csvMP)
                            csvMPs.add(csvMP)
                        }
                        csvMPs.join("\n")
                        csvUtils.writeAlignmentManualProcedureCsv(nameFileCSV, csvMPs, envs)
                    }
                }
            }
        }
    }

    private void writeConflictCsvFile(String releaseVersionTarget, String releaseVersionSource) {
        String conflicts = "Resource,Vendor,Date\n"
        conflicts += gitUtils.conflictFiles().replace("\n", ",,\n")
        dsl.echo(conflicts)
        dsl.dir("manual_procedures/${releaseVersionTarget}/${ALIGNMENT_FOLDER}") {
            dsl.writeFile file: "${releaseVersionSource}_#${dsl.BUILD_NUMBER}_Conflicts.csv", text: conflicts
        }
    }

    private String createPullRequest(String sourceBranch, String targetBranch) {
        Configuration cfg = Configuration.getInstance()
        String repositoryUrl = cfg.getMapValue(MAP_KEY_REPOSITORY_URL)
        String repositoryCredentialId = cfg.getMapValue(MAP_KEY_REPOSITORY_CREDENTIAL_ID)

        Map pullRequest = githubUtils.getPullRequests(repositoryUrl, sourceBranch, targetBranch, repositoryCredentialId)
        String prDescriptionString = "Automatic branch alignment from master to ${targetBranch}"

        String resultPRCreate

        if (pullRequest.isEmpty()) {
            try {
                def prJsonResponse = githubUtils.createPullRequest(repositoryUrl, sourceBranch, targetBranch, repositoryCredentialId, prDescriptionString)
                if (prJsonResponse) {
                    def jsonObjPr = this.dsl.readJSON text: prJsonResponse

                    if (jsonObjPr.number) {
                        resultPRCreate = JOB_RESULT_SUCCESS
                    }
                } else {
                    dsl.error("No commit for pull request")
                }
            } catch (Exception e) {
                dsl.echo("Pull Request from \"${sourceBranch}\" to \"${targetBranch}\" was not created, check")
                resultPRCreate = JOB_RESULT_FAILURE
            }
        } else {
            setUnstable("Pull request from \"${sourceBranch}\" to \"${targetBranch}\" already exists")
            resultPRCreate = JOB_RESULT_SUCCESS
        }
        return resultPRCreate
    }

    private void createPullRequestJob(def alignmentJob) {
        boolean localmerged = this.mergeLocal(alignmentJob[MAP_KEY_SOURCE_BRANCH], alignmentJob[MAP_KEY_TARGET_BRANCH], alignmentJob[MAP_KEY_RELEASE_VERSION_SOURCE], alignmentJob[MAP_KEY_RELEASE_VERSION_TARGET], alignmentJob[MAP_KEY_TARGET_ENV])
        if (localmerged) {
            String resultPRCreate = this.createPullRequest(alignmentJob[MAP_KEY_SOURCE_BRANCH], alignmentJob[MAP_KEY_TARGET_BRANCH])
            alignmentJob[MAP_KEY_CREATE_PR_JOB_RESULT] = resultPRCreate
        } else {
            alignmentJob[MAP_KEY_CREATE_PR_JOB_RESULT] = JOB_RESULT_FAILURE
        }
    }

    private void launchPullRequestAcceptanceJob(Map value, String jobName) {
        String prSourceBranch = value[MAP_KEY_SOURCE_BRANCH]
        String prTargetBranch = value[MAP_KEY_TARGET_BRANCH]

        def jobBuild = dsl.build job: jobName,
                wait: true,
                quietPeriod: 0,
                propagate: false,
                parameters: [
                        [$class: 'StringParameterValue', name: MAP_KEY_SOURCE_BRANCH_NAME, value: prSourceBranch],
                        [$class: 'StringParameterValue', name: MAP_KEY_TARGET_BRANCH_NAME, value: prTargetBranch],
                        [$class: 'StringParameterValue', name: MAP_KEY_TARGET_ENVIRONMENT, value: value[MAP_KEY_TARGET_ENV]],
                        [$class: 'StringParameterValue', name: MAP_KEY_TEST_LEVEL, value: value[MAP_KEY_TEST_LEV]],
                        [$class: 'StringParameterValue', name: MAP_KEY_RELEASE_VERSION, value: value[MAP_KEY_RELEASE_VERSION_TARGET]],
                        [$class: 'BooleanParameterValue', name: MAP_KEY_SKIP_VALIDATE, value: false]
                ]

        def jobResult = jobBuild.getResult()
        dsl.echo "Build of ${jobName} between ${prSourceBranch} and ${prTargetBranch} returned result: ${jobResult}"
        value[MAP_KEY_ACCEPT_PR_JOB_RESULT] = jobResult
    }

    private Map schedulePullRequestAcceptanceJob() {
        Configuration cfg = Configuration.getInstance()
        def alignmentJobs = cfg.getMapValue(MAP_KEY_ALIGNMENT_JOBS)
        String jobName = cfg.getMapValue(MAP_KEY_JOB_NAME)
        assert jobName.endsWith(JOB_CM_BRANCHALIGNMENT_SUFFIX)
        String prAcceptanceJobName = jobName.replace(JOB_CM_BRANCHALIGNMENT_SUFFIX, JOB_CM_ACCEPTANCE_PR_SUFFIX)

        Map prAcceptanceJobs = [:]

        alignmentJobs.each { alignmentJob ->
            if (alignmentJob.value[MAP_KEY_CREATE_PR_JOB_RESULT].equals(JOB_RESULT_SUCCESS) && alignmentJob.value[MAP_KEY_ACCEPT_PR_JOB_RESULT] == null) {
                String srcBranchName = alignmentJob.value[MAP_KEY_SOURCE_BRANCH]
                String tgtBranchName = alignmentJob.value[MAP_KEY_TARGET_BRANCH]

                if (alignmentJob.value[MAP_KEY_ACCEPT_PR]) {
                    prAcceptanceJobs["prAcceptanceJob_${srcBranchName}_${tgtBranchName}"] = { launchPullRequestAcceptanceJob(alignmentJob.value, prAcceptanceJobName) }
                } else {
                    dsl.echo("Pull request from \"${srcBranchName}\" to \"${tgtBranchName}\" has acceptPR set to " + alignmentJob.value[MAP_KEY_ACCEPT_PR])
                }
            }
        }

        return prAcceptanceJobs
    }

    private void launchDeployJob(Map value) {
        String jobName = "ita-salesforce-Deploy_TEST"
        String prTargetBranch = value[MAP_KEY_TARGET_BRANCH]

        def jobBuild = dsl.build job: jobName,
                wait: true,
                quietPeriod: 0,
                propagate: false,
                parameters: [
                        [$class: 'StringParameterValue', name: MAP_KEY_BRANCH_NAME, value: prTargetBranch],
                        [$class: 'StringParameterValue', name: MAP_KEY_START_HASH, value: ''],
                        [$class: 'StringParameterValue', name: MAP_KEY_END_HASH, value: ''],
                        [$class: 'BooleanParameterValue', name: MAP_KEY_VALIDATE_ONLY, value: false],
                        [$class: 'StringParameterValue', name: MAP_KEY_TARGET_ENVIRONMENT, value: value[MAP_KEY_TARGET_ENV]],
                        [$class: 'StringParameterValue', name: MAP_KEY_TEST_LEVEL, value: value[MAP_KEY_TEST_LEV]],
                        [$class: 'StringParameterValue', name: MAP_KEY_RELEASE_VERSION, value: value[MAP_KEY_RELEASE_VERSION_TARGET]],
                        [$class: 'BooleanParameterValue', name: MAP_KEY_SKIP_SCA, value: false]
                ]

        def jobResult = jobBuild.getResult()
        dsl.echo "Build of ${jobName} deploy to ${prTargetBranch} returned result: ${jobResult}"
        value[MAP_KEY_DEPLOY_JOB_RESULT] = jobResult
    }

    private Map scheduleDeployJob() {
        Configuration cfg = Configuration.getInstance()
        def alignmentJobs = cfg.getMapValue(MAP_KEY_ALIGNMENT_JOBS)

        Map deployJobs = [:]

        alignmentJobs.each { alignmentJob ->
            if (alignmentJob.value[MAP_KEY_ACCEPT_PR_JOB_RESULT].equals(JOB_RESULT_SUCCESS) && alignmentJob.value[MAP_KEY_DEPLOY_JOB_RESULT] == null) {
                String srcBranchName = alignmentJob.value[MAP_KEY_SOURCE_BRANCH]
                String tgtBranchName = alignmentJob.value[MAP_KEY_TARGET_BRANCH]

                if (alignmentJob.value[MAP_KEY_DEPLOY]) {
                    deployJobs["deployJob_${srcBranchName}_${tgtBranchName}"] = { launchDeployJob(alignmentJob.value) }
                } else {
                    dsl.echo("Alignment from \"${srcBranchName}\" to \"${tgtBranchName}\" has deployPR set to " + alignmentJob.value[MAP_KEY_DEPLOY])
                }
            }
        }

        return deployJobs
    }

    Map <String, ArrayList<ArrayList<String>>> loadManualProceduresCsv(String releaseVersion) {
        Configuration cfg = Configuration.getInstance()
        def map = [:]
        Map <String, ArrayList<ArrayList<String>>> mpTot = new HashMap<>()
        def slfcConfigs = cfg.getMapValue(MAP_KEY_SF_CONFIGS)
        def prodEnvironments = cfg.getMapValue(MAP_KEY_PROD_ENVIRONMENTS)
        def testEnvironments = cfg.getMapValue(MAP_KEY_TEST_ENVIRONMENTS)
        def envs = testEnvironments + prodEnvironments
        def vendors = slfcConfigs.vendors
        ArrayList<ArrayList<String>> mp = new ArrayList<>()

        vendors.each { vendor ->
            String pathMP = "manual_procedures/${releaseVersion}/${vendor}/${releaseVersion}_Manual_Procedures_${vendor}.csv"
            if (dsl.fileExists(pathMP)) {
                mp = csvUtils.read(pathMP, envs, false)
                map.put(vendor.toString(), mp.toString())

                mpTot.put(vendor.toString(), mp)
            }

            dsl.echo("mpTot : ${mpTot}")
        }

        return mpTot
    }

    boolean manualProceduresExistInTargetCsv(def mpLine, def vendorMap) {

        def sourceTuple = this.getManualProcedureTuple(mpLine)
        def targetTuple

        boolean mpEquals = false
        vendorMap.each { mpTargetLine ->
            if (!mpEquals) {
                targetTuple = this.getManualProcedureTuple(mpTargetLine)
                mpEquals = (sourceTuple.equals(targetTuple))
            }
        }
        return mpEquals
    }

    def getManualProcedureTuple(mpLine) {
        def tuple = []

        String fileName = csvUtils.getRecordFilename(mpLine)
        String phase = csvUtils.getRecordPhase(mpLine)
        boolean hasAttachment = csvUtils.getRecordHasAttachment(mpLine)
        String duration = csvUtils.getRecordDuration(mpLine)
        String requester = csvUtils.getRecordRequester(mpLine)

        tuple.add("filename: ${fileName}, phaseMP : ${phase}, hasAttachment: ${hasAttachment}, duration: ${duration}, requester: ${requester}")
        return tuple
    }

    void printFinalJobResults() {
        Configuration cfg = Configuration.getInstance()
        def alignmentJobs = cfg.getMapValue(MAP_KEY_ALIGNMENT_JOBS)

        def successList = []
        def failedList = []

        alignmentJobs.each { key, value ->
            if (value[MAP_KEY_CREATE_PR_JOB_RESULT].equals(null)) {
                value[MAP_KEY_CREATE_PR_JOB_RESULT] = NOT_AVAILABLE
            }
            if (value[MAP_KEY_ACCEPT_PR_JOB_RESULT].equals(null)) {
                value[MAP_KEY_ACCEPT_PR_JOB_RESULT] = NOT_AVAILABLE
            }
            if (value[MAP_KEY_DEPLOY_JOB_RESULT].equals(null)) {
                value[MAP_KEY_DEPLOY_JOB_RESULT] = NOT_AVAILABLE
            }

            boolean failed = (value[MAP_KEY_CREATE_PR_JOB_RESULT].equals(JOB_RESULT_FAILURE) || value[MAP_KEY_ACCEPT_PR_JOB_RESULT].equals(JOB_RESULT_FAILURE) || value[MAP_KEY_DEPLOY_JOB_RESULT].equals(JOB_RESULT_FAILURE))

            String result = "\t-" + value[MAP_KEY_SOURCE_BRANCH] + " -> " + value[MAP_KEY_TARGET_BRANCH] + "\n"
            result += "\t\t- PR Creation: " + value[MAP_KEY_CREATE_PR_JOB_RESULT] + "\n"
            result += "\t\t- PR Acceptance: " + value[MAP_KEY_ACCEPT_PR_JOB_RESULT] + "\n"
            result += "\t\t- Deploy: " + value[MAP_KEY_DEPLOY_JOB_RESULT] + "\n"

            if (failed) {
                failedList.add(result)
            } else {
                successList.add(result)
            }
        }

        String printResult = "------------ FINAL JOBS RESULT ------------\n"
        if (!successList.isEmpty()) {
            printResult += "Success alignments:\n" + successList.join("\n") + "\n"
        }
        if (!failedList.isEmpty()) {
            printResult += "Failed alignments:\n" + failedList.join("\n") + "\n"
        }
        printResult += "--------------------------------------------"

        dsl.echo(printResult)

        cfg.addEntryToMap(MAP_KEY_SLACK_MESSAGE, printResult, false)

        if (!failedList.isEmpty()) {
            if (successList.isEmpty()) {
                dsl.error("Only failed alignments. Please, check.")
            } else {
                setUnstable("Some failed alignments. Please, check.")
            }
        }
    }

}
