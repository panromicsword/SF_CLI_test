#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.technology

class PMDUtils implements Serializable {
    private def dsl

    PMDUtils(def dsl) {
        this.dsl = dsl
    }

    void runStaticCodeAnalysis(String runPath, String scanPath, String language, String reportFormat) {
        String reportName = "pmdReport_${dsl.env.BUILD_NUMBER}.${reportFormat}"

        try {
            dsl.sh """
                cd ${runPath}
                /opt/pmd/pmd-bin/bin/run.sh pmd -d ${scanPath} -R /opt/pmd/${language}rules/quickstart.xml -f ${reportFormat} -shortnames -l ${language} > ${reportName} 2>/dev/null | true
            """
        } finally {
            if (dsl.fileExists("${reportName}")) {
                dsl.echo("File named \"${reportName}\" archived successfully")
            } else {
                dsl.echo("File named \"${reportName}\" not found. Archive artifact procedure skipped")
            }
        }
    }
}
