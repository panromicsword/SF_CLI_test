#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.technology

import com.cloudbees.groovy.cps.NonCPS
import groovy.xml.XmlUtil
import net.sf.json.JSONArray

import static it.sky.dp.jenkins.salesforce.Constants.*

class SalesforceUtils extends AbstractSFUtils implements Serializable {
    final String ANT_BUILD_FILENAME = "build.xml"
    final String ENV_DEPENDENT_FOLDER = "env-dependent"
    final String DEVOPS_FOLDER = "devops"
    private CsvSalesforceUtils csvUtil

    SalesforceUtils(def dsl) {
        super(dsl)
        tagSuffix = "SLFC"
        this.csvUtil = new CsvSalesforceUtils(dsl)
    }

    // PRE DEPLOY METHODS /////////////////////////////////////////////////////

    void checkManualsFolderExists(String path, String version) {
        if (!dsl.fileExists("${path}/manual_procedures/${version}")) {
            dsl.error("Directory \"${version}\" does not exists in \"${path}/manual_procedures\"")
        }
    }

    void checkReleaseFolderOk(String path, String version) {
        this.checkManualsFolderExists(path, version)

        String pathObjects = "${path}/manual_procedures/${version}/metadata/objects"

        if (dsl.fileExists(pathObjects)) {
            def objectsList = utils.getFolderDirectoryList(pathObjects, "", "2")
            dsl.echo("objectsList -->" + objectsList)
            objectsList.each {
                if (it.toUpperCase().endsWith("/RECORDTYPE")) {
                    dsl.error("Invalid \"recordType\" directory manual_procedures/${version}/metadata/objects/${it}, should be \"recordTypes\".")
                }
            }
        }
    }

    void copyResources(String path, String hashFrom, String hashTo, String version) {
        dsl.echo("--- Copying Salesforce delta...")
        this.copyStandardResources(path, hashFrom, hashTo, version)
        this.copyNonStandardResources(path)
        this.copyManualProceduresMetadata(path, version)
        dsl.echo("--- Salesforce delta copied")
    }

    private void copyStandardResources(String path, String hashFrom, String hashTo, String version) {
        String profilesPath = "${path}/manual_procedures/${version}/metadata/profiles"
        sanitizeProfiles(profilesPath)

        dsl.sh """
            cd ${path}
            git config core.quotepath off

            git diff-tree --no-commit-id --name-only -r --diff-filter=d ${hashFrom} ${hashTo} | grep \"[^.xml]\$" | grep -v \"^vlocity_components/\" | xargs -I {} cp --parents {} ${path}/delta/ 2>/dev/null | true
            git diff-tree --no-commit-id --name-only -r --diff-filter=d ${hashFrom} ${hashTo} | grep \"[.xml]\$"  | grep -v \"^vlocity_components/\" | xargs -I {} cp --parents {} ${path}/delta/ 2>/dev/null | true

            find ./delta -type f | sed \"s/\\/delta//\" | xargs -I {} cp --parents {}-meta.xml ${path}/delta 2>/dev/null | true
            find ./delta -type f | sed \"s/\\/delta//\" | grep \"[.xml]\$\" | sed \"s/-meta.xml//\" | xargs -I {} cp --parents {} ${path}/delta 2>/dev/null | true
        """
    }

    private void copyNonStandardResources(String path) {
        String[] resources = ["aura", "lwc", "staticresources", "objectTranslations"]
        String deltaSrcFolder = "delta/force-app/main/default"

        for (resource in resources) {
            String maxDepth = ""
            if ("staticresources".equals(resource)) {
                maxDepth = "-maxdepth 1"
            }

            dsl.sh """
                cd ${path}
                if [ -d \"${deltaSrcFolder}/${resource}\" ]; then
                    cd ./${deltaSrcFolder}/${resource}
                    find . ${maxDepth} -type d | sed \"s/.//\" | xargs -I {} cp -r ${path}/force-app/main/default/${resource}{} .
                fi
            """
        }

        copyStaticresources(path)
    }

    private void copyStaticresources(String path) {
        String deltaSrcFolder = "delta/force-app/main/default"
        String staticresourcesFolder = "staticresources"

        // 1) ---- copy non modified resources for modified meta
        def deltaStaticResMeta = utils.getFolderFileList("./${deltaSrcFolder}/${staticresourcesFolder}", "xml")
        deltaStaticResMeta = deltaStaticResMeta.findAll { it.endsWith(".resource-meta.xml") }

        // loop on pattern to add files that match the pattern
        deltaStaticResMeta.each {
            String pattern = it.replace(".resource-meta.xml", "")

            def srcStaticResFilesByPattern = utils.getFolderFileList("${path}/force-app/main/default/${staticresourcesFolder}", "*", pattern)
            def srcStaticResDirsByPattern = utils.getFolderDirectoryList("${path}/force-app/main/default/${staticresourcesFolder}", pattern, "0")

            def srcStaticResToCopy = srcStaticResFilesByPattern + srcStaticResDirsByPattern
            srcStaticResToCopy = srcStaticResToCopy.unique() - it // it = the resource-meta.xml

            srcStaticResToCopy.each {
                dsl.sh "cp -av ${path}/force-app/main/default/${staticresourcesFolder}/${it} ${path}/${deltaSrcFolder}/${staticresourcesFolder}"
            }
        }

        // 2) ---- copy non modified meta for modified resources
        dsl.sh """
            cd ${path}
            if [ -d \"${deltaSrcFolder}/${staticresourcesFolder}\" ]; then
                cd ./${deltaSrcFolder}/${staticresourcesFolder}
                find ./* -type f | sed \"s/\\.\\///\" | cut -d\".\" -f1 | xargs -I {} cp ${path}/force-app/main/default/${staticresourcesFolder}/{}.resource-meta.xml . 2>/dev/null | true
                find ./* -type d | sed \"s/\\.\\///\" | cut -d\".\" -f1 | xargs -I {} cp ${path}/force-app/main/default/${staticresourcesFolder}/{}.resource-meta.xml . 2>/dev/null | true
            fi
        """
    }

    private void copyManualProceduresMetadata(String path, String version) {
        String deltaSourceFolder = "${path}/delta/manual_procedures/${version}/metadata"

        if (this.folderExistsAndHasElements(deltaSourceFolder)) {
            String deltaDestFolder = "${path}/delta/force-app/main/default"

            dsl.sh """
                mkdir -p ${deltaDestFolder}
                cd ${deltaSourceFolder} && find . -type f | xargs -I {} cp --parents -v {} ${deltaDestFolder}
            """
        }
    }

    void checkMetadataOk(String path) {
        String metadataFolder = "${path}/force-app/main/default"

        String profilesFolder = metadataFolder + "/profiles"
        boolean profilesExists = this.folderExistsAndHasElements(profilesFolder)

        String labelsFolder = metadataFolder + "/labels"
        boolean labelsExists = this.folderExistsAndHasElements(labelsFolder)

        // TODO implementare check anche sulla presenza di recordtype dentro gli objects che potrebbero esserci su delta/force-app/main/default

        if (profilesExists || labelsExists) {
            dsl.error("An invalid folder has been found in delta, search for profiles, labels or recordtype into \"force-app/main/default\" path")
        }
    }

    void placeholdersReplace(String path, String targeEnvironment) {
        final String PLACEHOLDER_PROPERTIES_FILENAME = "placeholder.properties"

        if (dsl.fileExists("${path}/delta/force-app/main/${ENV_DEPENDENT_FOLDER}")) {
            dsl.echo("targeEnvironment-> ${targeEnvironment}")
            String targetEnvPrefix = "${targeEnvironment}-"

            if (dsl.fileExists("${path}/${DEVOPS_FOLDER}/${PLACEHOLDER_PROPERTIES_FILENAME}")) {
                String placeholdersTxt = dsl.readFile "${path}/${DEVOPS_FOLDER}/${PLACEHOLDER_PROPERTIES_FILENAME}"

                String[] placeholdersArray = placeholdersTxt.split("\n")

                placeholdersArray.each { couple ->
                    boolean validPlaceholder = (couple?.trim()) && (couple.startsWith(targetEnvPrefix))
                    if (validPlaceholder) {
                        String[] keyValuePair = couple.split("=")
                        String key = keyValuePair[0].replace(targetEnvPrefix, "")

                        String value = ""
                        if (keyValuePair.length > 1) {
                            value = keyValuePair[1].replace("\"", "")
                            value = value.replace("/", "\\/")
                        }

                        dsl.sh "find ${path}/delta/force-app/main/${ENV_DEPENDENT_FOLDER} -type f -print0 | xargs -0 sed -i 's/{_${key}_}/${value}/g' "
                    }
                }

                dsl.sh "cp -rv ${path}/delta/force-app/main/${ENV_DEPENDENT_FOLDER}/. ${path}/delta/force-app/main/default"
                dsl.sh "rm -rf ${path}/delta/force-app/main/${ENV_DEPENDENT_FOLDER}/"
            } else {
                dsl.error("\"${PLACEHOLDER_PROPERTIES_FILENAME}\" file in ${DEVOPS_FOLDER} folder not exists")
            }
        } else {
            dsl.echo("No ${ENV_DEPENDENT_FOLDER} files are prensent in delta")
        }
    }

    private def getFolderTestList(String path) {
        String fileListStr = ""

        try {
            fileListStr = dsl.sh(returnStdout: true, script: "cd ${path}; egrep -lr --include=*.cls \"@isTest\"").trim()
        } catch (exception) {
        }

        return fileListStr.split("\n").findAll { it != "" }
    }

    private void warningFalseTest(String falseTestName) {
        dsl.echo("WARNING: test class \"${falseTestName}\" is not an actual test (no \"@isTest\" notation)")
    }

    private def getLocalTestForEntity(String path, String entityName, def allTests, def testJsonConfig) {
        final String TEST_CLASS_SUFFIX = "Test"
        def entityTestList = []

        final String sourceClassesPath = "${path}/force-app/main/default/classes"
        String testFileName = "${entityName}${TEST_CLASS_SUFFIX}.cls"
        String testFileNameUnderscore = "${entityName}_${TEST_CLASS_SUFFIX}.cls"

        // naming convention "Test"
        if (testFileName in allTests) {
            entityTestList.add(testFileName)
        } else {
            if (dsl.fileExists("${sourceClassesPath}/${testFileName}")) {
                this.warningFalseTest(testFileName)
            }
        }
        // naming convention "_Test"
        if (testFileNameUnderscore in allTests) {
            entityTestList.add(testFileNameUnderscore)
        } else {
            if (dsl.fileExists("${sourceClassesPath}/${testFileNameUnderscore}")) {
                this.warningFalseTest(testFileNameUnderscore)
            }
        }

        // json catalog config management
        def clsConfig = testJsonConfig."${entityName}"
        if (clsConfig) {
            if (clsConfig instanceof JSONArray) {
                clsConfig.each { testCls ->
                    if (testCls) {
                        String testClass = "${testCls}.cls"
                        if (testClass in allTests) {
                            entityTestList.add(testClass)
                        } else {
                            this.warningFalseTest(testClass)
                        }
                    }
                }
            } else {
                String testClass = "${clsConfig}.cls"
                if (testClass in allTests) {
                    entityTestList.add(testClass)
                } else {
                    this.warningFalseTest(testClass)
                }
            }
        }

        return entityTestList
    }

    def collectTestClasses(String path) {
        final String TEST_CATALOG_FILENAME = "tests-catalog.json"

        def testClsList = []

        if (dsl.fileExists("${path}/${DEVOPS_FOLDER}/${TEST_CATALOG_FILENAME}")) {
            def testErrors = []
            def testWarning = []
            def testJsonConfig = dsl.readJSON file: "${path}/${DEVOPS_FOLDER}/${TEST_CATALOG_FILENAME}"

            final String sourceClassesPath = "${path}/force-app/main/default/classes"
            final String delta_classPath = "${path}/delta/force-app/main/default/classes"
            final String delta_triggerPath = "${path}/delta/force-app/main/default/triggers"

            // all tests
            def allTests = this.getFolderTestList(sourceClassesPath)

            // all classes in delta
            def deltaFileList = utils.getFolderFileList(delta_classPath, "cls")

            // test classes in delta
            def deltaTestList = this.getFolderTestList(delta_classPath)

            // add the test classes already in delta
            testClsList.addAll(deltaTestList)

            // check env dependent
            def envTestList = this.getFolderTestList("${path}/force-app/main/${ENV_DEPENDENT_FOLDER}/classes")
            if (envTestList.size() > 0) {
                dsl.error("Test classes not allowed in \"${ENV_DEPENDENT_FOLDER}/classes\"")
            }

            // all triggers
            def deltaTriggerList = utils.getFolderFileList(delta_triggerPath, "trigger")
            // triggers
            deltaTriggerList.each { trigger ->
                if (trigger?.trim()) {
                    String triggerName = trigger.replace(".trigger", "")
                    def localTestList = getLocalTestForEntity(path, triggerName.trim(), allTests, testJsonConfig)

                    if (localTestList.size() > 0) {
                        testClsList.addAll(localTestList)
                    } else {
                        testWarning.add(trigger)
                    }
                }
            }
            if (testWarning.size() > 0) {
                dsl.echo("---------- WARNING: No test class found for entity/ies ----------\n" + testWarning +
                        "\n-----------------------------------------------------------------")
            }

            // classes in delta NO test
            def cleanDeltaFileList = deltaFileList - deltaTestList
            // classes
            cleanDeltaFileList.each { cls ->
                if (cls?.trim()) {
                    String clsName = cls.replace(".cls", "")
                    def localTestList = getLocalTestForEntity(path, clsName.trim(), allTests, testJsonConfig)

                    if (localTestList.size() > 0) {
                        testClsList.addAll(localTestList)
                    } else {
                        testErrors.add(cls)
                    }
                }
            }
            if (testErrors.size() > 0) {
                dsl.error("No test class found for class/es: " + testErrors)
            }
        } else {
            dsl.error("Test catalog ${TEST_CATALOG_FILENAME} file in ${DEVOPS_FOLDER} folder not exists")
        }

        return testClsList
    }

    Map<String, ArrayList<ArrayList<String>>> getDeltaManualProceduresMap(String path, String version, def vendors, def devEnvironments) {
        Map<String, ArrayList<ArrayList<String>>> deltaMPMap = new HashMap<>()

        String manualProceduresDeltaPath = "${path}/delta/manual_procedures/${version}"

        vendors.each { vendor ->
            String mpFile = "${version}_Manual_Procedures_${vendor}.csv"

            if (dsl.fileExists("${manualProceduresDeltaPath}/${vendor}")) {
                if (dsl.fileExists("${manualProceduresDeltaPath}/${vendor}/${mpFile}")) {
                    dsl.echo("File \"${mpFile}\" found in ${manualProceduresDeltaPath}/${vendor} path")

                    ArrayList<ArrayList<String>> records = csvUtil.read("${manualProceduresDeltaPath}/${vendor}/${mpFile}", devEnvironments, false)
                    deltaMPMap.put(mpFile, records)
                } else {
                    dsl.error("File \"${mpFile}\" has not been found in ${manualProceduresDeltaPath}/${vendor} path")
                }
            } else {
                dsl.echo("Folder \"${vendor}\" not found in ${manualProceduresDeltaPath} path")
            }
        }

        return deltaMPMap
    }

    def validateManualProcedures(String path, def version, Map<String, ArrayList<ArrayList<String>>> manualProceduresMap, String targetEnv) {
        String manualProceduresDeltaPath = "${path}/delta/manual_procedures/${version}"

        def errorList = []

        manualProceduresMap.each { mpFile ->
            String mpCsvFilename = mpFile.key
            String vendor = mpCsvFilename.substring(mpCsvFilename.lastIndexOf("_") + 1).replace(".csv", "")

            mpFile.value.each { mpFileLine ->
                String fileName = csvUtil.getRecordFilename(mpFileLine)
                if (fileName != csvUtil.getCOLUMN_FILENAME()) {
                    boolean hasAttachment = csvUtil.getRecordHasAttachment(mpFileLine)
                    boolean hasToBeExecutedInEnv = csvUtil.getHasToBeExecutedInEnv(mpFileLine, targetEnv)
                    boolean isPhaseValid = csvUtil.isExecutionPhaseValid(mpFileLine)
                    String duration = csvUtil.getRecordDuration(mpFileLine)

                    if (!isPhaseValid) {
                        errorList.add("Execution phase not valid for row: " + mpFileLine)
                    }

                    if (duration?.trim()) {
                        try {
                            int mpDuration = duration.toInteger()
                        } catch (Exception e) {
                            errorList.add("Invalid manual procedure duration \"" + duration + "\" for row: " + mpFileLine + " - " + e.getMessage())
                        }
                    } else {
                        errorList.add("Empty manual procedure duration for row: " + mpFileLine)
                    }

                    if (hasToBeExecutedInEnv) {
                        if (fileName && !dsl.fileExists("${manualProceduresDeltaPath}/${vendor}/${fileName}")) {
                            errorList.add("Manual procedure file \"${manualProceduresDeltaPath}/${vendor}/${fileName}\" does not exists")
                        }

                        if (hasAttachment) {
                            String mpFileNameNoExt = fileName.take(fileName.lastIndexOf('.'))
                            def attachmentList = utils.getFolderFileList("${manualProceduresDeltaPath}/${vendor}", "", mpFileNameNoExt)
                            if (attachmentList.size() < 2) {
                                errorList.add("No attachment found for manual procedure \"${mpFileNameNoExt}\"")
                            }
                        }
                    }
                } else {
                    dsl.echo("[validateManualProcedures] Discarded Manual procedure header line:\n${mpFileLine}")
                }
            }
        }

        if (errorList.size() > 0) {
            dsl.error(errorList.join("\n"))
        }
    }

    // CONVERSION METHODS /////////////////////////////////////////////////////


    void convertSources(String path, String slfcUrl, String salesforceVersion) {
        this.copySfdxProjectFile(path, slfcUrl, salesforceVersion)

        dsl.sh """
            cd ${path}
            mkdir -p retrieveUnpackaged
            sf project convert source --source-dir force-app --output-dir retrieveUnpackaged/
        """
    }

    void addCustomLabels(String path, String slfcUrl, String salesforceVersion) {
        this.copySfdxProjectFile(path, slfcUrl, salesforceVersion)

        String labelsFilepath = "delta/force-app/main/default/labels/CustomLabels.labels-meta.xml"
        if (dsl.fileExists(labelsFilepath)) {
            dsl.sh """
                cd ${path}
                sf sfpowerkit:source:customlabel:buildmanifest -p ${labelsFilepath} -x retrieveUnpackaged/package.xml
            """
        }
    }

    void checkForExistingPackage(String path, String version) {
        if (dsl.fileExists("${path}/manual_procedures/${version}/package.xml")) {
            dsl.echo "WARNING: a static \"package.xml\" file was found into ${path}/manual_procedures/${version} path. The auto-generated one will be replace by that"
            dsl.sh """
                cd ${path}
                mv retrieveUnpackaged/package.xml retrieveUnpackaged/autogenerated-package.xml
                cp -a manual_procedures/${version}/package.xml retrieveUnpackaged/.
            """
        }
    }

    void prepareStoreFile(String path) {
        if (dsl.fileExists("${path}/delta/force-app")) {
            dsl.sh """
                mkdir -p store/Salesforce
                cp -a ${path}/delta/force-app ${path}/store/Salesforce/DX
                cp -a ${path}/retrieveUnpackaged ${path}/store/Salesforce/MDAPI
            """
        }

        // validate log
        def buildLogFiles = dsl.findFiles glob: "validate*_build_log.xml"
        if (buildLogFiles.length > 0) {
            dsl.sh """
                cd ${path}
                mkdir -p store/Salesforce/validate
                cp -a ${path}/*_build_log.xml ${path}/store/Salesforce/validate/.
            """
        }

        //backup
        if (dsl.fileExists("${path}/backup/package.xml")) {
            dsl.sh """
                cd ${path}
                cp -a ${path}/backup ${path}/store/backup
            """
        }

        // sfdc reports
        def reportFile = dsl.findFiles glob: "sfdcReport_*.xlsx"
        if (reportFile.length > 0) {
            dsl.sh """
                cd ${path}
                mkdir -p store/quality
                cp -a ${path}/sfdcReport_*.xlsx ${path}/store/quality/.
            """
        }

        if (dsl.fileExists("${path}/CoverageReports")) {
            dsl.sh """
                cd ${path}
                mkdir -p store/quality
                cp -a ${path}/CoverageReports ${path}/store/quality/.
            """
        }
    }

    // ANT MIGRATION TOOL METHODS /////////////////////////////////////////////

    void validate(String path, String slfcUrl, String credentialsId, String testLevel, def testClsList, String logFileSuffix, boolean doBackup) {
        // Use sf CLI for validation
        String testLevelParam = testLevel ? "--test-level ${testLevel}" : ""
        String testClassesParam = (testClsList && testClsList.size() > 0) ? "--tests " + testClsList.collect { it.replace('.cls', '') }.join(',') : ""
        String deployDir = "retrieveUnpackaged"
        String logFileName = logFileSuffix ? "validate_${logFileSuffix}_sfcli.log" : "validate_sfcli.log"

        dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialsId, usernameVariable: "USERNAME", passwordVariable: "PASSWORD"]]) {
            dsl.sh """
                cd ${path}
                echo y | sf org login username --username $USERNAME --set-default --alias ciOrg
                sf project deploy validate --target-org ciOrg --source-dir ${deployDir} ${testLevelParam} ${testClassesParam} --json | tee ${logFileName}
            """
        }
        // Optionally, backup logic can be implemented here if needed
    }

    void generateValidationReport(String path, String deployId, String reportName, boolean reportPMDOk, String salesforceVersion, String slfcUrl, String credentialsId) {
        String manualReportJson = "manual_procedures.json"
        String qualityReportCommand = "sfdc-report-cli --deployId=${deployId} --reportFile=${reportName} --reportManualProcedures=${manualReportJson}"

        // vlocity catalog
        String vlocityCatalogJson = "vlocity_components.json"
        if (dsl.fileExists("${path}/${vlocityCatalogJson}")) {
            qualityReportCommand += " --reportVlocityComponents=${vlocityCatalogJson}"
        }

        if (reportPMDOk) {
            qualityReportCommand += " --reportPMD=pmdReport_${dsl.env.BUILD_NUMBER}.json"
        }

        // info commit
        String infoCommitJson = "info_commits.json"
        qualityReportCommand += " --reportInfoCommit=${infoCommitJson}"

        try {
            dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialsId, usernameVariable: "USERNAME", passwordVariable: "PASSWORD"]]) {
                dsl.withEnv(["SLFC_URL=" + slfcUrl, "SLFC_USERNAME=" + dsl.USERNAME, "SLFC_PASSWORD=" + dsl.PASSWORD, "SLFC_VERSION=" + salesforceVersion]) {
                    dsl.sh """
                        cd ${path}
                        ${qualityReportCommand}
                    """
                }
            }
        } finally {
            utils.archiveFile(path, reportName, true, true)
        }
    }

    void deploy(String path, String slfcUrl, String credentialsId, String testLevel, def testClsList) {
        // Use sf CLI for deployment
        String testLevelParam = testLevel ? "--test-level ${testLevel}" : ""
        String testClassesParam = (testClsList && testClsList.size() > 0) ? "--tests " + testClsList.collect { it.replace('.cls', '') }.join(',') : ""
        String deployDir = "retrieveUnpackaged"

        dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialsId, usernameVariable: "USERNAME", passwordVariable: "PASSWORD"]]) {
            dsl.sh """
                cd ${path}
                echo y | sf org login username --username $USERNAME --set-default --alias ciOrg
                sf project deploy start --target-org ciOrg --source-dir ${deployDir} ${testLevelParam} ${testClassesParam} --json
            """
        }
    }

    void quickDeploy(String path, String slfcUrl, String credentialsId, String deployId) {
        // Use sf CLI for quick deploy (using a previously validated deployId)
        dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialsId, usernameVariable: "USERNAME", passwordVariable: "PASSWORD"]]) {
            dsl.sh """
                cd ${path}
                echo y | sf org login username --username $USERNAME --set-default --alias ciOrg
                sf project deploy quick --target-org ciOrg --job-id ${deployId} --json
            """
        }
    }

    private void copyBuildXmlFile(String path) {
        if (dsl.fileExists("${path}/${ANT_BUILD_FILENAME}")) {
            dsl.echo("\"${ANT_BUILD_FILENAME}\" already present")
        } else {
            dsl.sh("mv ${path}/${DEVOPS_FOLDER}/${ANT_BUILD_FILENAME} ${path}/.")
        }
    }

    private void copySfdxProjectFile(String path, String slfcUrl, String salesforceVersion) {
        final String SFDX_PROJECT_FILENAME = "sfdx-project.json"

        if (dsl.fileExists("${path}/${SFDX_PROJECT_FILENAME}")) {
            dsl.echo("\"${SFDX_PROJECT_FILENAME}\" already present")
        } else {
            String templateSfxProject = "${path}/${DEVOPS_FOLDER}/${SFDX_PROJECT_FILENAME}"
            if (dsl.fileExists(templateSfxProject)) {
                String fileStr = dsl.readFile templateSfxProject
                fileStr = fileStr.replace("{_packageDirectoriesPath_}", "delta/force-app/main/default")
                fileStr = fileStr.replace("{_sfdcLoginUrl_}", slfcUrl)
                fileStr = fileStr.replace("{_sourceApiVersion_}", salesforceVersion)

                dsl.writeFile(file: "${path}/${SFDX_PROJECT_FILENAME}", text: fileStr)
            } else {
                dsl.error("Unable to find template file \"${templateSfxProject}\"")
            }
        }

        this.printFile("${path}/${SFDX_PROJECT_FILENAME}")
    }

    @NonCPS
    private def internalWriteBuildXmlTestClasses(def xmlFileStr, String targetName, def testClasses) {
        def testClassesList = testClasses.unique()

        def tmpXml = new XmlSlurper().parseText(xmlFileStr)
        def node = tmpXml.target.find { it.@name == targetName }
        if (node) {
            def sfDeployNode = node.children().find { it.name() == 'deploy' }
            if (sfDeployNode) {
                testClassesList.each { tc ->
                    String testClass = tc.replace(".cls", "")
                    def newNode = new XmlSlurper().parseText("<runTest>${testClass}</runTest>")
                    sfDeployNode.appendNode(newNode)
                }
            }
        }

        return XmlUtil.serialize(tmpXml)
    }

    private void writeBuildXmlTestClasses(String path, String targetName, def testClasses) {
        String xmlFileName = "${path}/${ANT_BUILD_FILENAME}"

        String xmlFileStr = dsl.readFile(file: xmlFileName)

        String newXmlStr = internalWriteBuildXmlTestClasses(xmlFileStr, targetName, testClasses)
        dsl.writeFile(file: xmlFileName, text: newXmlStr)

        this.printFile(xmlFileName)
    }

    private void runAntMigrationTool(String path, String slfcUrl, String credentialsId, String command, String testLevel, String fileSuffix, String deployId) {
        String fileLogName = command
        if (fileSuffix?.trim()) {
            fileLogName += "_" + fileSuffix
        }
        fileLogName += "_build_log.xml"

        try {
            dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialsId, usernameVariable: "USERNAME", passwordVariable: "PASSWORD"]]) {
                dsl.withEnv(["SFLC_URL=" + slfcUrl, "SFLC_USERNAME=" + dsl.USERNAME, "SFLC_PASSWORD=" + dsl.PASSWORD, "TEST_LEVEL=" + testLevel, "VALIDATION_ID=" + deployId]) {
                    dsl.sh """
                        cd ${path}
                        ant ${command} -logger org.apache.tools.ant.XmlLogger -verbose -logfile ${fileLogName}
                    """
                }
            }
        } finally {
            utils.archiveFile("${path}", "${fileLogName}", true, true)
        }
    }

    private void runAntBackup(String path, String slfcUrl, String credentialsId) {
        String retrieveFolderPath = "${path}/backup"
        String packageXmlFolder = "${path}/retrieveUnpackaged/package.xml"
        String fileLogName = "backup_log.xml"

        try {
            dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialsId, usernameVariable: "USERNAME", passwordVariable: "PASSWORD"]]) {
                dsl.withEnv(["SFLC_URL=" + slfcUrl, "SFLC_USERNAME=" + dsl.USERNAME, "SFLC_PASSWORD=" + dsl.PASSWORD, "SFLC_RETRIVE_TARGET=" + retrieveFolderPath, "SFLC_UNPACKAGED=" + packageXmlFolder]) {
                    dsl.sh """
                        cd ${path}
                        mkdir -p backup
                        ant backup -logger org.apache.tools.ant.XmlLogger -verbose -logfile ${fileLogName}
                    """
                }
            }
        } finally {
            utils.archiveFile(path, fileLogName, true, true)
        }
    }

    String getDeployId(String path, String fileSuffix) {
        String fileLogName = "validate"
        if (fileSuffix?.trim()) {
            fileLogName += "_" + fileSuffix
        }
        fileLogName += "_build_log.xml"

        if (!dsl.fileExists("${path}/${fileLogName}")) {
            dsl.error("Log file not found, Deploy ID retrieve failed")
        }

        String deployId = dsl.sh(script: "cat ${fileLogName} | grep -Po '(?<=\\bRequest ID for the current deploy task:\\s)(\\w+)'", returnStdout: true)
        if (deployId?.trim()) {
            deployId = deployId.trim()
            dsl.echo("Execution Deploy ID: " + deployId)
        } else {
            dsl.error("Request ID not found for Code Coverage calculation")
        }

        return deployId
    }

    ArrayList<ArrayList<String>> getManualProceduresRecord(Map<String, ArrayList<ArrayList<String>>> csvMap, String phase, String environment) {
        return csvUtil.getRecords(csvMap, phase, environment)         
    }


    String getManualProceduresStrSummary(Map<String, ArrayList<ArrayList<String>>> mpMap, String phase, String environment) {
        def stdOutput = []

        mpMap.each { mpFile ->
            String mpCsvFilename = mpFile.key
            Integer counter = 1

            mpFile.value.each { mpFileLine ->
                boolean hasToBeExecutedInEnv = csvUtil.getHasToBeExecutedInEnv(mpFileLine, environment)
                String recPhase = csvUtil.getRecordPhase(mpFileLine)
                
                if (hasToBeExecutedInEnv && phase.equals(recPhase.toUpperCase())) {
                    String recordDescr = csvUtil.getRecordsDescriptionWithFileName(mpCsvFilename, mpFileLine, counter++)
                    stdOutput.add(recordDescr)
                }
            }
        }

        return stdOutput.join("\n")
    }

    String getManualProceduresToBeExecutedStr(Map<String, ArrayList<ArrayList<String>>> mpMap, String phase, String environment) {
        def stdOutput = []
        stdOutput.add("==============================================================")
        stdOutput.add("       Manual procedures - ${phase} ${environment}")
        stdOutput.add("--------------------------------------------------------------")

        String mpSummary = getManualProceduresStrSummary(mpMap, phase, environment)
        stdOutput.add(mpSummary.trim())

        stdOutput.add("==============================================================")

        return stdOutput.join("\n")
    }

    def sanitizeProfiles(String profileFolder) {
        dsl.echo("sanitizeProfiles: checking for profiles in \"${profileFolder}\"")
        def profileFiles
        dsl.sh("chmod -R a+w \"${profileFolder}\"")
        dsl.dir(profileFolder) {
            dsl.sh("ls -lhar")
            profileFiles = dsl.findFiles(glob: "*.profile-meta.xml")
        }
        dsl.echo("sanitizeProfiles: profileFiles->" + profileFiles)

        profileFiles.each {
            def filePath = "${profileFolder}/${it.path}"
            dsl.echo("sanitizeProfiles: profile path->\"${filePath}\"")
            String profileXml = dsl.readFile filePath

            String cleanProfileXml = profileXml.replaceAll(/<userPermissions>[\w\W]*<\/userPermissions>/, "")
            cleanProfileXml = cleanProfileXml.replaceAll(/<userPermissions\/>/, "")

            cleanProfileXml = cleanProfileXml.replaceAll(/<loginIpRanges>[\w\W]*<\/loginIpRanges>/, "")
            cleanProfileXml = cleanProfileXml.replaceAll(/<loginIpRanges\/>/, "")

            cleanProfileXml = cleanProfileXml.replaceAll(/<loginHours>[\w\W]*<\/loginHours>/, "")
            cleanProfileXml = cleanProfileXml.replaceAll(/<loginHours\/>/, "")

            cleanProfileXml = cleanProfileXml.replaceAll(/<userLicense>[\w\W]*<\/userLicense>/, "")
            cleanProfileXml = cleanProfileXml.replaceAll(/<userLicense\/>/, "")

            // empty lines
            cleanProfileXml = cleanProfileXml.replaceAll(/(?ms)^(?:[\t ]*(?:\r?\n|\r))+/, "")

            if (profileXml != cleanProfileXml) {
                dsl.echo("sanitizeProfiles: writing new profile path->\"${filePath}\"")
                dsl.writeFile file: filePath, text: cleanProfileXml
            }
        }
    }

    // UTILITY METHODS ////////////////////////////////////////////////////////

    private void printFile(String filepath) {
        if (dsl.fileExists(filepath)) {
            String fileStr = dsl.readFile filepath

            String printStr = "\n############# " + filepath + "#############\n\n" + fileStr + "\n#######################################\n"

            dsl.echo(printStr)
        } else {
            dsl.echo("Filepath ${filepath} doesn't exists")
        }
    }

}
