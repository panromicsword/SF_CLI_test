package it.sky.dp.jenkins.salesforce.common

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.jenkins.blueocean.rest.impl.pipeline.FlowNodeWrapper
import io.jenkins.blueocean.rest.impl.pipeline.PipelineNodeGraphVisitor
import org.jenkinsci.plugins.workflow.actions.ErrorAction
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

class Utils implements Serializable {
    private def dsl

    Utils(def dsl) {
        this.dsl = dsl
    }

    def getFolderFileList(String path) {
        return internalGetFolderFileList(path, "*", "*")
    }

    def getFolderFileList(String path, String fileExtension) {
        return internalGetFolderFileList(path, fileExtension, "*")
    }

    def getFolderFileList(String path, String fileExtension, String fileFilter) {
        return internalGetFolderFileList(path, fileExtension, fileFilter)
    }

    private def internalGetFolderFileList(String path, String fileExtension, String fileFilter) {
        String ext = fileExtension
        if ("".equals(ext.trim())) {
            ext = "*"
        }
        String filter = fileFilter
        if ("".equals(filter.trim())) {
            filter = "*"
        }
        String fileListStr = ""
        String searchParam = "*${filter}*.${ext}"

        try {
            if (dsl.fileExists(path)) {
                fileListStr = dsl.sh(returnStdout: true, script: "cd ${path}; find ${searchParam} -type f 2>/dev/null").trim()
            } else {
                dsl.echo("WARNING: Path \"${path}\" does not exists")
            }
        } catch (exception) {
            dsl.echo("Error during getFolderFileList method on path \"${path}\"")
        }

        def list = fileListStr.split("\n").findAll { it != "" && it != ".gitignore" }
        return list
    }

    def getFolderContentList(String path) {
        def folderContent = dsl.sh(returnStdout: true, script: "cd ${path}; find . -type f 2>/dev/null").trim()
        def list = folderContent.split("\n").findAll { it != "" && !it.endsWith(".gitignore") }
        return list
    }

    void ensureFolderExist(String path, boolean shouldExist, boolean emptyFolder) {
        if (!dsl.fileExists(path)) {
            if (shouldExist) {
                dsl.echo "WARNING: the folder \"${path}\" doesn't exist"
            }
            dsl.sh """
                mkdir -p ${path}
            """
            if (emptyFolder) {
                dsl.sh """
                    cd ${path}
                    touch .gitignore
                """
            }
        }
    }

    def getFolderDirectoryList(String path, String dirFilter, String depth) {
        String filter = dirFilter
        if ("".equals(filter.trim())) {
            filter = "*"
        }
        String dirListStr = ""

        String maxDepth = ""
        if (!"".equals(depth.trim())) {
            maxDepth = "-maxdepth ${depth}"
        }

        try {
            String searchParam = "*${filter}*"
            dirListStr = dsl.sh(returnStdout: true, script: "cd ${path}; find ${searchParam} ${maxDepth} -type d 2>/dev/null").trim()
        } catch (exception) {
            dsl.echo("Error during getFolderDirectoryList method on path \"${path}\"")
        }

        def list = dirListStr.split("\n").findAll { it != "" }
        return list
    }

    def archiveFile(String path, fileName, boolean allowEmptyArchive, boolean fingerprint) {
        if (dsl.fileExists("${path}/${fileName}")) {
            dsl.archiveArtifacts artifacts: fileName, allowEmptyArchive: allowEmptyArchive, fingerprint: fingerprint
            dsl.echo("File named \"${fileName}\" archived successfully")
        } else {
            dsl.echo("File named \"${fileName}\" not found. Archive artifact procedure skipped")
        }
    }

    @NonCPS
    def getBuildUser() {
        String userId = "automated"
        def cause = dsl.currentBuild.rawBuild.getCause(Cause.UserIdCause)

        if (cause) {
            userId = cause.getUserId()
        }
        return userId
    }

    @NonCPS
    static String getNonSuccessStageResults(RunWrapper build) {
        def nonSuccessStr = []
        def nonSuccessStages = getNonSuccessStages(build)

        nonSuccessStages.each { stage ->
            String stageResult = stage["result"]
            String stageName = stage["displayName"]

            if ("FAILURE".equals(stageResult)) {
                nonSuccessStr.add("Stage: \"${stageName}\" - Errors: ${stage["errors"]}")
            } else {
                nonSuccessStr.add("Stage: \"${stageName}\"")
            }
        }

        return nonSuccessStr.join("\n")
    }

    // Get information about all stages, including the failure causes.
    //
    // Returns a list of maps: [[id, displayName, result, errors]]
    // The 'errors' member is a list of unique exceptions.
    @NonCPS
    static List<Map> getStageResults(RunWrapper build) {

        // Get all pipeline nodes that represent stages
        def visitor = new PipelineNodeGraphVisitor(build.rawBuild)
        def stages = visitor.pipelineNodes.findAll { it.type == FlowNodeWrapper.NodeType.STAGE }

        return stages.collect { stage ->

            // Get all the errors from the stage
            def errorActions = stage.getPipelineActions(ErrorAction)
            def errors = errorActions?.collect { it.error }?.unique()

            return [
                    id         : stage.id,
                    displayName: stage.displayName,
                    result     : "${stage.status.result}",
                    errors     : errors
            ]
        }
    }

    // Get information of all failed stages
    @NonCPS
    static List<Map> getNonSuccessStages(RunWrapper build) {
        return getStageResults(build).findAll { it.result == "FAILURE" || it.result == "ABORTED" || it.result == "UNSTABLE" }
    }

    static String encodeJson(String inputJson) {
        def tmpJson = new JsonSlurper().parseText(inputJson)
        return JsonOutput.toJson(tmpJson)
    }

    // get a string escaped for sh invocation
    static String getShEscapedText(String text) {
        String goodText = text.replaceAll('[^-A-Za-z0-9._\\s]') { "'\\${it}'" }

        return '\'' + goodText + '\''
    }

    def checkDiskSpace() {
        final int WARNING_SPACE = 90
        final String UNABLE_TO_CHECK_SPACE = "WARING: unable to check disk space"
        try {
            dsl.sh("df -h")
            dsl.sh("ls -lha")
            dsl.sh("du -shc /home/jenkins/agent/workspace/salesforce_automation/*")
            String spaceCmd = dsl.sh(returnStdout: true, script: "df -h").trim()
            def tokens = spaceCmd.split("\n")

            def driveSpace = tokens.findAll { it.endsWith(" /") }
            if (driveSpace.size() > 0) {
                String s = driveSpace[0]
                assert s.contains("%")
                def tokens2 = s.split("\\s+")
                def percent = tokens2.findAll { it.contains("%") }
                String totalSpace = tokens2[1]
                if (percent.size() > 0) {
                    String percentStr = percent[0]
                    int perc = (percentStr.replace("%", "")) as int
                    String spaceMessage = "DISK SPACE CHECK: the drive occupied space is ${perc}% of ${totalSpace} total"
                    if (perc > WARNING_SPACE) {
                        dsl.unstable("${spaceMessage} and exceeds the warning level (${WARNING_SPACE}%)")
                    } else {
                        dsl.echo(spaceMessage)
                    }
                } else {
                    dsl.echo(UNABLE_TO_CHECK_SPACE)
                }
            } else {
                dsl.echo(UNABLE_TO_CHECK_SPACE)
            }
        } catch (Exception e) {
            dsl.echo(UNABLE_TO_CHECK_SPACE)
        }
    }

}
