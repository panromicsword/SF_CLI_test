#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.technology

class VlocityUtils extends AbstractSFUtils implements Serializable {

    VlocityUtils(def dsl) {
        super(dsl)
        tagSuffix = "VL"
    }

    void deploy(String path, String slfcUrl, String credentialsId) {
        String fileLogName = "VlocityBuildLog.yaml"
        String fileLogName2 = "VlocityBuildErrors.log"

        try {
            dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialsId, usernameVariable: "USERNAME", passwordVariable: "PASSWORD"]]) {
                String escapedUser = utils.getShEscapedText(dsl.USERNAME)
                String escapedPwd = utils.getShEscapedText(dsl.PASSWORD)

                String command = '#!/bin/bash\n cd ' + path + '; ' +
                        'git config --global --add safe.directory ' + path + '; ' +
                        'vlocity -sf.loginUrl https://' + slfcUrl + ' -sf.username ' + escapedUser + ' -sf.password ' + escapedPwd + ' -job ./devops/vlocity-deploy-datapack.yaml cleanOrgData --simpleLogging true; ' +
                        'vlocity -sf.loginUrl https://' + slfcUrl + ' -sf.username ' + escapedUser + ' -sf.password ' + escapedPwd + ' -job ./devops/vlocity-deploy-datapack.yaml packDeploy --simpleLogging true' 
                        
                dsl.sh script: command
            }
        } finally {
            utils.archiveFile(path, fileLogName, true, true)
            utils.archiveFile(path, fileLogName2, true, true)
        }
    }

    void prepareStoreFile(String path) {
        if (dsl.fileExists("${path}/delta/vlocity_components")) {
            dsl.sh """
                cd ${path}
                mkdir -p store/Vlocity/vlocity_components
                cp -a ${path}/delta/vlocity_components ${path}/store/Vlocity
                cd ${path}/vlocity_components
            """
        }

        //report
        def reportFile = dsl.findFiles glob: "sfdcReport_*.xlsx"
        if (reportFile.length > 0) {
            dsl.sh """
                cd ${path}
                mkdir -p store/quality
                cp -a ${path}/sfdcReport_*.xlsx ${path}/store/quality/.
            """
        }
    }

    void copyResources(String path, String hashFrom, String hashTo, String version) {
        dsl.echo("--- Copying Vlocity delta...")
        dsl.sh """
            cd ${path}
            git config core.quotepath off

            git diff-tree --no-commit-id --name-only -r --diff-filter=d ${hashFrom} ${hashTo} | grep \"^vlocity_components/\" | xargs -I {} cp --parents {} ${path}/delta/ 2>/dev/null | true
        """
        dsl.echo("--- Vlocity delta copied")
    }

    def getVlocityComponents(String workingPath) {
        def files
        dsl.dir(workingPath) {
            files = dsl.sh(script: "find vlocity_components -type f", returnStdout: true)
        }
        def fileList = files.split("\n").findAll { it != "" && !it.endsWith(".gitignore") }

        return fileList
    }

}
