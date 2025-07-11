#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.technology

import it.sky.dp.jenkins.salesforce.common.Utils

abstract class AbstractSFUtils implements Serializable {
    protected def dsl
    protected Utils utils
    protected String tagSuffix = ""

    AbstractSFUtils(def dsl) {
        this.dsl = dsl
        this.utils = new Utils(dsl)
    }

    void createDeltaFolder(String path) {
        dsl.sh "mkdir -p ${path}/delta"
    }

    protected boolean folderExistsAndHasElements(String path) {
        boolean result = false

        if (dsl.fileExists(path)) {
            def fContent = utils.getFolderContentList(path)
            result = (fContent.size() > 0)
        }

        return result
    }

    boolean checkDeltaOk(String workingPath, String folder, String description, boolean logResults) {
        boolean diffExists = this.folderExistsAndHasElements("${workingPath}/${folder}")

        if (logResults) {
            if (diffExists) {
                def files
                dsl.dir(workingPath) {
                    files = dsl.sh(script: "find ${folder} -type f", returnStdout: true)
                }
                def fileList = files.split("\n").findAll { it != "" && !it.endsWith(".gitignore") }

                dsl.echo("---------- ${description} File list ----------\n" +
                        fileList.join("\n") +
                        "\n---------- End of ${description} File list ----------")
            } else {
                dsl.echo("-------- No ${description} diff found --------")
            }
        }

        return diffExists
    }

    String getSourceAlert(String workingPath, String folder, def filePatterns) {
        String result = null
        def msg = []

        boolean diffExists = this.folderExistsAndHasElements("${workingPath}/${folder}")
        if (diffExists) {
            filePatterns.each { pattern ->
                def files
                dsl.dir(workingPath) {
                    files = dsl.sh(script: "find ${folder} -type f -wholename \"${pattern}\"", returnStdout: true)
                }
                def fileList = files.split("\n").findAll { it != "" }
                if (fileList.size() > 0) {
                    msg.add("File/s matching pattern \"${pattern}\":" + fileList)
                }
            }
        }

        if (msg.size() > 0) {
            result = msg.join("\n")
        }

        return result
    }

    String getTagValue(String targetEnvironment) {
        assert (!targetEnvironment?.trim() != "")

        if (!targetEnvironment?.trim()) {
            dsl.error("createTagValue input is invalid")
        }

        Date date = new Date()
        String timeStamp = date.format("yyyyMMdd") + date.format("HHmmss")

        return "${targetEnvironment}_${timeStamp}_${tagSuffix}"
    }

    String getTagPattern(String targetEnvironment) {
        assert (!targetEnvironment?.trim() != "")

        return "${targetEnvironment}_*${tagSuffix}"
    }

    abstract void copyResources(String path, String hashFrom, String hashTo, String version)

}
