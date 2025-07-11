package it.sky.dp.jenkins.salesforce.technology

import groovy.json.JsonOutput
import java.text.SimpleDateFormat

import static it.sky.dp.jenkins.salesforce.Constants.*

class ElasticsearchUtils {
    private def dsl

    final String MAPPING_RECAP = "{\"mappings\":{\"salesforce\":{\"properties\":{\"build\":{\"properties\":{\"branchName\":" +
            "{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"buildNumber\":{\"type\":\"long\"},\"jobName\":" +
            "{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"releaseVersion\":{\"type\":\"text\",\"fields\":" +
            "{\"keyword\":{\"type\":\"keyword\"}}},\"startDate\":{\"type\":\"date\"},\"targetEnvironment\":{\"type\":" +
            "\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"userId\":{\"type\":\"text\",\"fields\":{\"keyword\":" +
            "{\"type\":\"keyword\"}}}}},\"recap\":{\"properties\":{\"averageCodeCoverage\":{\"type\":\"float\"},\"branchName\":" +
            "{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"buildUserId\":{\"type\":\"text\",\"fields\":" +
            "{\"keyword\":{\"type\":\"keyword\"}}},\"checkOnly\":{\"type\":\"boolean\"},\"componentErrors\":{\"type\":\"long\"}," +
            "\"componentsDeployed\":{\"type\":\"long\"},\"componentsTotal\":{\"type\":\"long\"},\"deployId\":{\"type\":\"text\"," +
            "\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"endHash\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":" +
            "\"keyword\"}}},\"minCodeCoverage\":{\"type\":\"float\"},\"postDurationToExecute\":{\"type\":\"long\"}," +
            "\"postManualProceduresToExecute\":{\"type\":\"long\"},\"preDurationToExecute\":{\"type\":\"long\"}," +
            "\"preManualProceduresToExecute\":{\"type\":\"long\"},\"quickDeployId\":{\"type\":\"text\",\"fields\":{\"keyword\":" +
            "{\"type\":\"keyword\"}}},\"releaseVersion\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}," +
            "\"skipSca\":{\"type\":\"boolean\"},\"startHash\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}," +
            "\"status\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"targetEnvironment\":{\"type\":" +
            "\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"testErrors\":{\"type\":\"long\"},\"testLevel\":" +
            "{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"testsCompleted\":{\"type\":\"long\"}," +
            "\"testsTotal\":{\"type\":\"long\"},\"totalDurationToExecute\":{\"type\":\"long\"},\"totalManualProceduresToExecute\":" +
            "{\"type\":\"long\"},\"validateOnly\":{\"type\":\"boolean\"},\"validationDatetime\":{\"type\":\"date\"}}}}}}}"
    final String MAPPING_VALIDATED_COMPONENTS = "{\"mappings\":{\"salesforce\":{\"properties\":{\"build\":{\"properties\":" +
            "{\"branchName\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"buildNumber\":{\"type\":" +
            "\"long\"},\"jobName\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"releaseVersion\":" +
            "{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"startDate\":{\"type\":\"date\"}," +
            "\"targetEnvironment\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"userId\":{\"type\":" +
            "\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}}},\"validatedComponents\":{\"properties\":" +
            "{\"componentType\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"filename\":{\"type\"" +
            ":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"fullname\":{\"type\":\"text\",\"fields\":" +
            "{\"keyword\":{\"type\":\"keyword\"}}}}}}}}}"
    final String MAPPING_COMPONENT_ERRORS = "{\"mappings\":{\"salesforce\":{\"properties\":{\"build\":{\"properties\":" +
            "{\"branchName\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"buildNumber\":{\"type\"" +
            ":\"long\"},\"jobName\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"releaseVersion\":" +
            "{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"startDate\":{\"type\":\"date\"}," +
            "\"targetEnvironment\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"userId\":{\"type\"" +
            ":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}}},\"componentErrors\":{\"properties\":{\"componentType\":" +
            "{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"filename\":{\"type\":\"text\",\"fields\":" +
            "{\"keyword\":{\"type\":\"keyword\"}}},\"fullname\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}," +
            "\"lineNumber\":{\"type\":\"long\"},\"problem\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}," +
            "\"problemType\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}}}}}}}"
    final String MAPPING_TEST_FAILURES = "{\"mappings\":{\"salesforce\":{\"properties\":{\"build\":{\"properties\":" +
            "{\"branchName\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"buildNumber\":{\"type\":" +
            "\"long\"},\"jobName\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"releaseVersion\":" +
            "{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"startDate\":{\"type\":\"date\"}," +
            "\"targetEnvironment\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"userId\":{\"type\"" +
            ":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}}},\"testFailures\":{\"properties\":{\"className\"" +
            ":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"message\":{\"type\":\"text\",\"fields\"" +
            ":{\"keyword\":{\"type\":\"keyword\"}}},\"methodName\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":" +
            "\"keyword\"}}},\"stacktrace\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}}}}}}}"
    final String MAPPING_TEST_EXECUTION = "{\"mappings\":{\"salesforce\":{\"properties\":{\"build\":{\"properties\":" +
            "{\"branchName\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"buildNumber\":{\"type\"" +
            ":\"long\"},\"jobName\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"releaseVersion\"" +
            ":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"startDate\":{\"type\":\"date\"}," +
            "\"targetEnvironment\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"userId\":{\"type\"" +
            ":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}}},\"testExecution\":{\"properties\":{\"className\"" +
            ":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"coverage%\":{\"type\":\"long\"}," +
            "\"locationsNotCovered\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"notCoveredRownums\":" +
            "{\"type\":\"long\"},\"totalRownums\":{\"type\":\"long\"}}}}}}}"
    final String MAPPING_PMD_REPORT = "{\"mappings\":{\"salesforce\":{\"properties\":{\"build\":{\"properties\":{\"branchName\"" +
            ":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"buildNumber\":{\"type\":\"long\"},\"jobName\"" +
            ":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"releaseVersion\":{\"type\":\"text\",\"fields\"" +
            ":{\"keyword\":{\"type\":\"keyword\"}}},\"startDate\":{\"type\":\"date\"},\"targetEnvironment\":{\"type\":\"text\"," +
            "\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"userId\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":" +
            "\"keyword\"}}}}},\"pmdReport\":{\"properties\":{\"beginColumn\":{\"type\":\"long\"},\"beginLine\":{\"type\":" +
            "\"long\"},\"description\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"endColumn\":" +
            "{\"type\":\"long\"},\"endLine\":{\"type\":\"long\"},\"fileName\":{\"type\":\"text\",\"fields\":{\"keyword\":" +
            "{\"type\":\"keyword\"}}},\"priority\":{\"type\":\"long\"},\"rule\":{\"type\":\"text\",\"fields\":{\"keyword\":" +
            "{\"type\":\"keyword\"}}},\"ruleSet\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}}}}}}}"
    final String MAPPING_MANUAL_PROCEDURES_BEFORE_ENV = "{\"mappings\":{\"salesforce\":{\"properties\":{\"build\":{\"properties\":" +
            "{\"branchName\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"buildNumber\":" +
            "{\"type\":\"long\"},\"jobName\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"releaseVersion\"" +
            ":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"startDate\":{\"type\":\"date\"},\"targetEnvironment\"" +
            ":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"userId\":{\"type\":\"text\",\"fields\":" +
            "{\"keyword\":{\"type\":\"keyword\"}}}}},\"manualProcedures\":{\"properties\":" + "{"

    final String MAPPING_MANUAL_PROCEDURES_AFTER_ENV = "\"duration\":{\"type\":\"long\"}," +
            "\"environment\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}," +
            "\"filename\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}," +
            "\"hasAttachments\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}," +
            "\"id\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}," +
            "\"pre/post\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}," +
            "\"requester\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}," +
            "\"toBeExecuted\":{\"type\":\"boolean\"}," +
            "\"vendorName\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}" +
            "}" +
            "}}}}}"

    final String CM_ENVIRONMENT_PLACEHOLDER = "@\$ENVIRONMENT\$@"
    final String CM_ENVIRONMENT_COL_MAPPING = "\"" + CM_ENVIRONMENT_PLACEHOLDER + "\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},"

    ElasticsearchUtils(def dsl) {
        this.dsl = dsl
    }

    void bulkInsertReleaseData(Map releaseData) {
        def monitoringJson = dsl.readJSON file: releaseData[MAP_KEY_ELASTICSEARCH_MONITORING_JSON_FILE_NAME]

        doBulkInsert(releaseData, monitoringJson.recap, "recap")

        doBulkInsert(releaseData, monitoringJson.validatedComponents, "validatedComponents")
        doBulkInsert(releaseData, monitoringJson.componentErrors, "componentErrors")
        doBulkInsert(releaseData, monitoringJson.testFailures, "testFailures")
        doBulkInsert(releaseData, monitoringJson.testExecution, "testExecution")
        doBulkInsert(releaseData, monitoringJson.pmdReport, "pmdReport")
        doBulkInsert(releaseData, monitoringJson.manualProcedures, "manualProcedures")
    }

    private void doBulkInsert(Map releaseData, def jsonRootElement, String payloadRootElementName) {
        boolean insertResult = bulkInsert(releaseData, jsonRootElement, payloadRootElementName)
        if (!insertResult && jsonRootElement) {
            dsl.echo("WARNING: elasticsearch bulk insert failed for element ${payloadRootElementName}\n\n" + jsonRootElement + "\n")
        }
    }

    private Map getBuildInfoMap(Map releaseData) {
        def buildInfo = [:]

        def dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
        def simpleDateFormat = new SimpleDateFormat(dateFormat)
        def dateStr = simpleDateFormat.format(new Date())

        buildInfo["branchName"] = releaseData[MAP_KEY_ELASTICSEARCH_BRANCH_NAME]
        buildInfo["userId"] = releaseData[MAP_KEY_ELASTICSEARCH_BUILD_USER_ID]
        buildInfo["startDate"] = dateStr
        buildInfo["releaseVersion"] = releaseData[MAP_KEY_ELASTICSEARCH_RELEASE_VERSION]
        buildInfo["targetEnvironment"] = releaseData[MAP_KEY_ELASTICSEARCH_TARGET_ENV]
        buildInfo["jobName"] = releaseData[MAP_KEY_ELASTICSEARCH_JOB_NAME]
        try {
            int buildNumberInt = releaseData[MAP_KEY_ELASTICSEARCH_BUILD_NUMBER].toInteger()
            buildInfo["buildNumber"] = buildNumberInt
        } catch (Exception e) {
            dsl.error("Error during the parse buildNumber to int")
        }

        return buildInfo
    }

    private String getRawIndexName(String jsonRootElement) {
        def chArray = []

        for (i in 0..<jsonRootElement.length()) {
            char ch = jsonRootElement.charAt(i)

            if (Character.isUpperCase(ch)) {
                chArray.add("-" + ch.toLowerCase())
            } else {
                chArray.add(ch)
            }
        }

        return chArray.join("")
    }

    String getIndexMapping(def releaseData, String payloadRootElementName) {
        String mapping
        def environments = releaseData[MAP_KEY_ELASTICSEARCH_ENVIRONMENTS]

        switch (payloadRootElementName) {
            case "recap":
                mapping = MAPPING_RECAP
                break

            case "validatedComponents":
                mapping = MAPPING_VALIDATED_COMPONENTS
                break

            case "componentErrors":
                mapping = MAPPING_COMPONENT_ERRORS
                break

            case "testFailures":
                mapping = MAPPING_TEST_FAILURES
                break

            case "testExecution":
                mapping = MAPPING_TEST_EXECUTION
                break

            case "pmdReport":
                mapping = MAPPING_PMD_REPORT
                break

            case "manualProcedures":
                def envMapping = ""
                environments.each {
                    def camelCaseEnv = "cm${it.toLowerCase().capitalize()}"
                    envMapping += CM_ENVIRONMENT_COL_MAPPING.replace(CM_ENVIRONMENT_PLACEHOLDER, camelCaseEnv)
                }

                mapping = MAPPING_MANUAL_PROCEDURES_BEFORE_ENV + envMapping + MAPPING_MANUAL_PROCEDURES_AFTER_ENV
                break

            default:
                dsl.echo("WARNING: index mapping not found for element ${payloadRootElementName}")
        }

        return mapping
    }

    boolean checkIndex(Map releaseData, String indexName, String payloadRootElementName) {
        boolean indexFound
        dsl.echo("Checking index ${indexName}...")
        String elasticsearchUrl = releaseData[MAP_KEY_ELASTICSEARCH_URL]

        try {
            def existsResponse = dsl.httpRequest consoleLogResponseBody: false,
                    contentType: 'APPLICATION_JSON',
                    httpMode: 'GET',
                    url: "${elasticsearchUrl}/${indexName}",
                    wrapAsMultipart: false,
                    validResponseCodes: '100:499'
            dsl.echo("existsResponse->" + existsResponse.status)

            indexFound = (existsResponse.status == 200)

            if (!indexFound) {
                String indexMapping = getIndexMapping(releaseData, payloadRootElementName)
                dsl.echo("indexMapping->\n" + indexMapping + "\n")

                dsl.echo("Creating index ${indexName}...")
                def createResponse = dsl.httpRequest consoleLogResponseBody: false,
                        contentType: 'APPLICATION_JSON',
                        httpMode: 'PUT',
                        requestBody: indexMapping,
                        url: "${elasticsearchUrl}/${indexName}?include_type_name=true",
                        wrapAsMultipart: false,
                        validResponseCodes: '100:399'
                dsl.echo("createResponse->" + createResponse.content)

                indexFound = (createResponse.status == 200)
            }
        } catch (Exception e) {
            dsl.echo("WARNING: Error creating index: " + e.getMessage())
        }

        if (indexFound) {
            dsl.echo("Index ${indexName} found")
        }

        return indexFound
    }

    private boolean insertElements(String elasticsearchUrl, def jsonRootElement, String payloadRootElementName, Map buildInfoMap, String indexName) {
        boolean result = false
        def elementList = []
        def indexJson = [:]

        dsl.echo("Adding ${payloadRootElementName} to index ${indexName}...")
        try {
            boolean entireElement = payloadRootElementName.toLowerCase().equals("recap")

            if (jsonRootElement) {
                indexJson["index"] = ["_index": "${indexName}", "_type": "salesforce"]

                if (entireElement) {
                    elementList.add(JsonOutput.toJson(indexJson))

                    def element = [:]
                    element["build"] = buildInfoMap
                    element[payloadRootElementName] = jsonRootElement
                    elementList.add(JsonOutput.toJson(element))
                } else {
                    jsonRootElement.each {
                        elementList.add(JsonOutput.toJson(indexJson))

                        def element = [:]
                        element["build"] = buildInfoMap
                        element[payloadRootElementName] = it
                        def jso = JsonOutput.toJson(element)
                        elementList.add(jso)
                    }
                }

                if (elementList.size() > 0) {
                    String bulkInsert = elementList.join("\n") + "\n"

                    def response = dsl.httpRequest consoleLogResponseBody: false,
                            contentType: 'APPLICATION_JSON',
                            httpMode: 'POST',
                            requestBody: bulkInsert,
                            url: "${elasticsearchUrl}/_bulk",
                            wrapAsMultipart: false,
                            validResponseCodes: '100:399'

                    if (response.content?.trim()) {
                        /*
                        // json obj parse (slower)
                        def jsonObj = this.dsl.readJSON text: response.content

                        result = !(jsonObj.errors)
                        */
                        // regex match (faster)
                        /*
                        Scripts not permitted to use staticMethod org.codehaus.groovy.runtime.DefaultGroovyMethods findAll java.lang.String java.lang.String. Administrators can decide whether to approve or reject this signature.
                         */
                        result = response.content.findAll("^\\{\"took\":[0-9]+,\"errors\":false,\"")
                    }
                    if (result) {
                        // one element for index declaration the other for index content
                        dsl.echo("Added ${elementList.size() / 2} elements to index ${indexName}")
                    }
                }
            } else {
                dsl.echo("No ${payloadRootElementName} elements to add to index ${indexName}")
                result = true
            }
        } catch (Exception e) {
            dsl.echo(e.getMessage())
        }

        return result
    }

    private String getIndexName(Map releaseData, String payloadRootElementName) {
        def dateFormat = "yyyy.MM"
        def simpleDateFormat = new SimpleDateFormat(dateFormat)
        def dateStr = simpleDateFormat.format(new Date())
        String targetEnv = releaseData[MAP_KEY_ELASTICSEARCH_TARGET_ENV]

        String rawIndexName = getRawIndexName(payloadRootElementName)
        String idxName = "sfdc-"

        String indexPrefix = releaseData[MAP_KEY_ELASTICSEARCH_INDEX_PREFIX]

        if (indexPrefix?.trim()) {
            idxName += "${indexPrefix}-"
        }
        idxName += "${rawIndexName}-${targetEnv}-${dateStr}"

        return idxName.toLowerCase()
    }

    private boolean bulkInsert(Map releaseData, def jsonRootElement, String payloadRootElementName) {
        boolean result = false
        Map buildInfo = getBuildInfoMap(releaseData)

        String targetEnvironment = releaseData[MAP_KEY_ELASTICSEARCH_TARGET_ENV]
        assert targetEnvironment

        String indexName = getIndexName(releaseData, payloadRootElementName)
        String elasticsearchUrl = releaseData[MAP_KEY_ELASTICSEARCH_URL]

        boolean indexOk = checkIndex(releaseData, indexName, payloadRootElementName)
        if (indexOk) {
            result = insertElements(elasticsearchUrl, jsonRootElement, payloadRootElementName, buildInfo, indexName)
        }

        return result
    }
}
