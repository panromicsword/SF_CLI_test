#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.technology
import static it.sky.dp.jenkins.salesforce.Constants.*
import it.sky.dp.jenkins.salesforce.common.Configuration

class ManualProceduresUtils extends AbstractSFUtils implements Serializable {
    private CsvSalesforceUtils csvUtil

    ManualProceduresUtils(def dsl) {
        super(dsl)
        this.csvUtil = new CsvSalesforceUtils(dsl)
    }

    @Override
    void copyResources(String path, String hashFrom, String hashTo, String version) {
        // nothing to do
    }

// PRE DEPLOY METHODS /////////////////////////////////////////////////////

    void prepareStoreFile(String path, String releaseVersion, Map<String, ArrayList<ArrayList<String>>> mpMap, def devEnvironments, def targetEnv) {
        this.copyMpProductionFiles(releaseVersion, mpMap, path, devEnvironments)

        if (targetEnv.equals(SLFC_ENV_PRODUCTION)) {
            if(dsl.fileExists("${path}/delta/manual_procedures/${releaseVersion}_PROD")) {
                dsl.sh """
                    cd ${path}
                    mkdir -p store/ManualProcedures/${releaseVersion}_PROD
                    cp -a ${path}/delta/manual_procedures/${releaseVersion}_PROD/. ${path}/store/ManualProcedures/${releaseVersion}_PROD
                """
            }
        } else {
            dsl.sh """
                cd ${path}
                mkdir -p store/ManualProcedures
                cp -a ${path}/delta/manual_procedures/. ${path}/store/ManualProcedures
            """
        }
    }

    void copyMpProdFiles(String releaseVersion, String vendor, String filename, String path) {
        if (dsl.fileExists("${path}/delta/manual_procedures/${releaseVersion}/${vendor}/${filename}")) {
            dsl.sh """
                cd ${path}
                mkdir -p delta/manual_procedures/${releaseVersion}_PROD/${vendor}
                cp -a ${path}/delta/manual_procedures/${releaseVersion}/${vendor}/"${filename}" ${path}/delta/manual_procedures/${releaseVersion}_PROD/${vendor}/.
            """
        }
    }

    void copyAttachmentProdFiles(String releaseVersion, String vendor, String filename, String path) {
        String attachments = filename.tokenize(".")[0]
        dsl.sh """
            cd ${path}
            cd ${path}/delta/manual_procedures/${releaseVersion}/${vendor}
            find . -name "${attachments}.*" -exec cp -av {} ${path}/delta/manual_procedures/${releaseVersion}_PROD/${vendor}/. \\;
            """
    }

    void copyMpProductionFiles(String releaseVersion, Map<String, ArrayList<ArrayList<String>>> mpMap, String path, def devEnvironments) {
        def mpMapPROD = [:]
        String vendorName
        String mpCsvFilename

        Configuration cfg = Configuration.getInstance()
        def prodEnvironments = cfg.getMapValue(MAP_KEY_PROD_ENVIRONMENTS)
        def testEnvironments = cfg.getMapValue(MAP_KEY_TEST_ENVIRONMENTS)
        def envs = testEnvironments + prodEnvironments
        def csvHeaders = csvUtil.initHeader(envs)
        csvUtil.setUpHeader(csvHeaders)

        mpMap.each { mpFile ->
            mpCsvFilename = mpFile.key
            vendorName = getVendorNameFromMPCsvFileName(mpCsvFilename)
            def prodVendorCsv = mpMap[mpCsvFilename]

            prodVendorCsv.each { mpFileLine ->
                boolean toExecuteInProd = csvUtil.getHasToBeExecutedInEnv(mpFileLine, "PROD")

                if (toExecuteInProd) {
                    String filename = csvUtil.getRecordFilename(mpFileLine)
                    def destValue = mpMapPROD[mpCsvFilename]
                    if (!destValue) {
                        mpMapPROD[mpCsvFilename] = [mpFileLine]
                    } else {
                        mpMapPROD[mpCsvFilename].add(mpFileLine)
                    }
                    this.copyMpProdFiles(releaseVersion, vendorName, filename, path)
                    boolean containsAttachment = csvUtil.getRecordHasAttachment(mpFileLine)
                    if (containsAttachment) {
                        this.copyAttachmentProdFiles(releaseVersion, vendorName, filename, path)
                    }
                }
            }
        }
        mpMapPROD.each { mpPRODFile ->
            String mpPRODCsvFilename = mpPRODFile.key
            vendorName = getVendorNameFromMPCsvFileName(mpPRODCsvFilename)
            def records = mpPRODFile.value

            dsl.sh("cd ${path}")
            dsl.sh("mkdir -p delta/manual_procedures/${releaseVersion}_PROD/${vendorName}")

            String mpPRODCsvFilePath = "${path}/delta/manual_procedures/${releaseVersion}_PROD/${vendorName}/${mpPRODCsvFilename}"

            csvUtil.writeManualProcedureCsv(mpPRODCsvFilePath, records, devEnvironments)
        }
    }

    protected String getVendorNameFromMPCsvFileName(String csvFileName) {
        def tokens = csvFileName.split("_")

        String vendorName = null
        if (tokens.size() > 3) {
            vendorName = (tokens[tokens.size() - 1]).toUpperCase().replace(".CSV", "")
        }

        return vendorName
    }
}
