#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.scm

class GitUtils implements Serializable {
    private def dsl

    GitUtils(def dsl) {
        this.dsl = dsl
    }

    void setConfigs() {
        dsl.sh """
            git config --global --replace-all user.email \"jenkins@example.com\"
            git config --global --replace-all user.name \"Jenkins CI\"
        """
    }

    void setRemoteUrl(String repositoryUrl, String credentialId) {
        String protocol = repositoryUrl.substring(0, repositoryUrl.indexOf("/") + 2)
        String gitEndpoint = repositoryUrl.substring(repositoryUrl.indexOf("/") + 2)

        /*dsl.sshagent(credentials: [credentialId]) {
            dsl.sh 'git remote set-url origin ' + repositoryUrl
        }*/

        dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialId, usernameVariable: "USERNAME", passwordVariable: "TOKEN"]]) {
            dsl.sh 'git remote set-url origin ' + protocol + dsl.USERNAME + ':' + dsl.TOKEN + '@' + gitEndpoint
        }
    }

    void checkoutBranch(String repositoryUrl, String branchName, String credentialId, String workingPath) {
        this.setConfigs()

        String protocol = repositoryUrl.substring(0, repositoryUrl.indexOf("/") + 2)
        String gitEndpoint = repositoryUrl.substring(repositoryUrl.indexOf("/") + 2)
        /*dsl.checkout([
                $class                           : "GitSCM",
                branches                         : [[name: branchName]],
                doGenerateSubmoduleConfigurations: false,
                extensions                       : [
                        [$class: 'LocalBranch', localBranch: branchName]
                ],
                submoduleCfg                     : [],
                userRemoteConfigs                : [[credentialsId: credentialId, url: repositoryUrl]]
        ])*/
        dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialId, usernameVariable: "USERNAME", passwordVariable: "TOKEN"]]) {
            boolean checkRemoteExists = this.checkRemoteExists()
              // Get some code from a GitHub repository
            if (checkRemoteExists) {
                dsl.echo ("REMOTE EXISTS")
                dsl.sh("""
                    git fetch --quiet --tags --prune --prune-tags
                    git checkout ${branchName}

                    echo "pulled the code"
                """)
            } else {
                dsl.echo ("REMOTE NOT EXISTS")
                dsl.sh script: "git init "
                dsl.sh script: 'git remote add origin ' + protocol + dsl.USERNAME + ':' + dsl.TOKEN + '@' + gitEndpoint 
                dsl.sh("""
                    git fetch --quiet --tags --prune --prune-tags
                    git checkout ${branchName}

                    echo "pulled the code"
                """)
            }
        }

        dsl.sh script: "git config --global --add safe.directory ${workingPath}"
    }

    boolean checkRemoteExists () {
        boolean remoteExists = false
        def items = dsl.sh(returnStdout: true, script: "ls -a")

        def arrayItems = items.split("\n")

        arrayItems.each { item ->
            if (item.trim().equals(".git")) {
                remoteExists = true
            }
        }

        return remoteExists
    }

    void lightCheckoutBranch(String repositoryUrl, String branchName, String credentialId, String workingPath) {
        dsl.checkout(
                changelog: false,
                poll: true,
                scm: [
                        $class                           : 'GitSCM',
                        branches                         : [[name: branchName]],
                        doGenerateSubmoduleConfigurations: false,
                        extensions                       : [
                                [
                                        $class   : "CloneOption",
                                        depth    : 1,
                                        noTags   : true,
                                        reference: '',
                                        shallow  : true,
                                        timeout  : 40
                                ]
                        ],
                        submoduleCfg                     : [],
                        userRemoteConfigs                : [[credentialsId: credentialId, url: repositoryUrl]]
                ]
        )
        dsl.sh script: "git config --global --add safe.directory ${workingPath}"
    }

    void tagSource(String credentialId, String tagName) {
        dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialId, usernameVariable: "USERNAME", passwordVariable: "PASSWORD"]]) {
        //dsl.withCredentials([dsl.sshUserPrivateKey(credentialsId: credentialId, keyFileVariable: 'key')]) {
            //dsl.sh(script: " git config core.sshCommand 'ssh -i " +  dsl.key +   " -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no'" , returnStdout: true)
            
            dsl.sh """
                git tag -a \"${tagName}\" -m \"Tag ${tagName}\"
                git push origin --tags -q
            """
        //}
        }
    }

    String getLastTagMatch(String matchString) {
        String lastTag = null

        try {
            lastTag = dsl.sh(script: "git describe --tag --abbrev=0 --match \"${matchString}\"", returnStdout: true)
        } catch (Exception e) {
            dsl.echo("An exception occured during the retrieve of the tag")
        }

        if (lastTag?.trim()) {
            lastTag = lastTag.trim()
        } else {
            dsl.error("No tag found for string ${matchString}")
        }
        return lastTag
    }

    String getHashOfTag(String tag) {
        String hash = dsl.sh(script: "git rev-list -1 ${tag}", returnStdout: true)

        if (hash?.trim()) {
            hash = hash.trim()
        } else {
            dsl.error("No hash found for tag ${tag}")
        }
        return hash
    }

    String getSourceHead(String repositoryUrl, String credentialId, String sourceBranch) {
        String devHead
        String devLastHash

        String protocol = repositoryUrl.substring(0, repositoryUrl.indexOf("/") + 2)
        String gitEndpoint = repositoryUrl.substring(repositoryUrl.indexOf("/") + 2)

        dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialId, usernameVariable: "USERNAME", passwordVariable: "TOKEN"]]) {
            devHead = dsl.sh(script: 'git ls-remote  ' + protocol + dsl.TOKEN + '@' + gitEndpoint + ' | grep refs/heads/' + sourceBranch, returnStdout: true)

            if (devHead?.trim()) {
                devHead = devHead.trim()
                devLastHash = devHead.split()[0]
            } else {
                dsl.error("No hash found for branch ${sourceBranch}")
            }
            return devLastHash
        }
    }

    boolean alreadyMerged(String sourceLastHash, String targetBranch, boolean isRemote) {
        String command
        String targetBr

        if (isRemote) {
            this.mergeSources(targetBranch)
            command = "git branch -a --contains ${sourceLastHash}"
            targetBr = "remotes/origin/${targetBranch}"
        } else {
            command = "git branch --contains ${sourceLastHash}"
            targetBr = targetBranch
        }

        String commandOutput = dsl.sh(script: command, returnStdout: true)
        def branches = commandOutput.split("\n").collect { it.replace(" ", "").trim() }.findAll { it != "" && it.trim().equals(targetBr) }
        boolean mergeExists = branches.size() > 0

        return mergeExists
    }

    void mergeSources(String branch) {
        try {
            dsl.sh 'git pull origin ' + branch
        } catch (Exception e) {
            dsl.error("There was an error during the merge between ${branch} into the local branch probably due to a conflict, please check")
        }
    }

    void cleanBranch(String branchName, boolean local, String workingPath) {
        String modifier = ""
        if (!local) {
            modifier = "origin/"
        }
        try {
            dsl.sh """
                git config --global --add safe.directory ${workingPath}
                git reset --hard ${modifier}${branchName}
                git clean -dfx
            """
        } catch (Exception e) {
            dsl.error("There was an error during the clean of the branch, please check")
        }
    }

    boolean checkBranchExist(String branchName, boolean isRemote) {
        boolean branchExist = false

        try {
            String grepFilter = branchName
            if (isRemote) {
                grepFilter = "remotes/origin/${branchName}"
            }
            String branchExists = dsl.sh(script: "git branch -a | grep -i ${grepFilter} | wc -l", returnStdout: true)

            branchExist = (!"0".equals(branchExists?.trim()))
        } catch (Exception e) {
            dsl.error("There was an error during the creation of the branch, please check")
        }

        return branchExist
    }

    void checkoutLocalBranch(String releaseBranchName, boolean branchExist, String workingPath) {
        try {
            String cmdSwitch = ""
            if (!branchExist) {
                cmdSwitch = "-b"
            }

            dsl.sh "git checkout ${cmdSwitch} ${releaseBranchName}"
            dsl.sh script: "git config --global --add safe.directory ${workingPath}"
        } catch (Exception e) {
            dsl.error("There was an error during the creation of the branch, please check")
        }
    }

    void commitAndPushBranch(String branch, boolean remoteBranchExist, String commitMessage) {
        try {
            dsl.sh "git add ."

            String diffCount = dsl.sh(script: "git diff --cached --numstat | wc -l", returnStdout: true)
            dsl.echo "diffCount: ${diffCount}"
            if (!"0".equals(diffCount?.trim())) {
                dsl.sh "git commit -m \"${commitMessage}\""
            } else {
                dsl.echo("Nothing to commit")
            }

            if (!"0".equals(diffCount?.trim()) || !remoteBranchExist) {
                String firstPush = ""
                if (!remoteBranchExist) {
                    firstPush = "--set-upstream"
                }

                dsl.sh "git push ${firstPush} origin ${branch}"
            }

        } catch (Exception e) {
            dsl.error("There was an error during the creation of the branch, please check")
        }
    }

    String conflictFiles() {
        String conflictFiles = dsl.sh(script: "git diff --name-only --diff-filter=U", returnStdout: true)

        return conflictFiles
    }

    void pull(String branch) {
        dsl.sh 'git pull origin ' + branch
    }

    def collectBranches(boolean isRemote, String attributes) {
        def branches = []
        String remoteAttribute = ""

        if (isRemote) {
            remoteAttribute = "-r"
        }

        try {
            def branchesStr = dsl.sh(script: "git branch ${remoteAttribute} ${attributes}", returnStdout: true)

            branches = branchesStr.split("\n")
            branches = branches.collect { it.trim() }
        } catch (Exception e) {
            dsl.error("There was an error during the branch collection")
        }

        return branches
    }

    def lastUpdateBranches(def branches, String period) {
        def periodBranches = []

        try {
            for (branch in branches) {
                // viene assunto se andato a buon fine almeno ho una riga con Date: e pertanto posso eliminarlo
                String lines = dsl.sh(script: "git log -1 --since='${period}' ${branch} | grep Date: | wc -l", returnStdout: true)
                if ("0".equals(lines?.trim())) {
                    periodBranches.add(branch)
                } else {
                    dsl.echo "Branch \"" + branch + "\" has at least a commit since this period"
                }
            }
        } catch (Exception e) {
            dsl.error("There was an error during the branch collection")
        }

        return periodBranches
    }

    def getTagsInfo() {
        def cmdOutput = dsl.sh(script: "git log --pretty='format:%ad|%D' | grep tag:", returnStdout: true)

        if (cmdOutput?.trim()) {
            cmdOutput = cmdOutput.trim()
        }

        return cmdOutput
    }

    void deleteLocalBranch(String branch) {
        try {
            dsl.sh "git branch -D ${branch}"
        } catch (Exception e) {
            dsl.error("There was an error during the branch deletion")
        }
    }

    void deleteRemoteBranches(def branches) {
        def errorBranch = []
        for (branch in branches) {
            if (branch?.trim()) {
                try {
                    //dsl.echo("git push --delete origin ${branch.replace("origin/", "")}")
                    dsl.sh "git push --delete origin ${branch.replace("origin/", "")}"
                } catch (Exception e) {
                    errorBranch.add(branch)
                }
            }
        }
        if (errorBranch.size() > 0) {
            dsl.unstable("There was an error during the branch deletion. Branch/es:\n" + errorBranch)
        }
    }

    void deleteRemoteTags(def tags) {
        def errorTags = []
        for (tag in tags) {
            if (tag?.trim()) {
                try {
                    //dsl.echo("git push origin :refs/tags/${tag}")
                    dsl.sh "git push origin :refs/tags/${tag}"
                } catch (Exception e) {
                    errorTags.add(tag)
                }
            }
        }
        if (errorTags.size() > 0) {
            dsl.unstable("There was an error during the tag deletion. Tag/s:\n" + errorTags)
        }
    }

    def getDiffFiles(String hashTo, String hashFrom, String filter) {
        String command = """git config core.quotepath off
                            git diff-tree --no-commit-id --name-only -r ${hashFrom} ${hashTo}"""

        if (filter?.trim()) {
            command += " --diff-filter=" + filter
        }

        def files = dsl.sh(script: command, returnStdout: true)

        def fileList = files.split("\n").findAll { it != "" && !it.endsWith(".gitignore") }
        dsl.echo("fileList -->" + fileList)

        return fileList
    }

    def getCommitAuthors(String hashTo, String hashFrom, def filename, String workingPath) {
        String infoAuthor = dsl.sh(script: "git log -s ${hashTo}...${hashFrom} --pretty='format:%an' '${workingPath}/${filename}'", returnStdout: true)

        def infoAuthorList = infoAuthor.split("\n").findAll { it != "" }
        infoAuthorList = infoAuthorList.unique()

        return infoAuthorList
    }

    def getRowsModifiedAndAuthors(String hashTo, String hashFrom, def filename, String workingPath) {
        def infoChangesMap = [:]

        if (dsl.fileExists(workingPath + "/" + filename)) {
            def infoAuthorList = this.getCommitAuthors(hashTo, hashFrom, filename, workingPath)

            infoChangesMap["authors"] = infoAuthorList

            String infoChanges = dsl.sh(script: "git diff --shortstat ${hashFrom} ${hashTo} '${workingPath}/${filename}'", returnStdout: true)

            dsl.echo("info changes --->" + infoChanges)

            def infoChangesList = infoChanges.split(",")

            int rowInsertion = 0
            int rowDeletion = 0

            infoChangesList.each {
                if (it.contains("(+)")) {
                    def infoInsertionList = it.trim().split(" ")
                    String insertion = infoInsertionList[0].trim()
                    rowInsertion = insertion.toInteger()
                }

                if (it.contains("(-)")) {
                    def infoInsertionList = it.trim().split(" ")
                    String deletion = infoInsertionList[0].trim()
                    rowDeletion = deletion.toInteger()
                }
            }

            infoChangesMap["insertion"] = rowInsertion
            infoChangesMap["deletion"] = rowDeletion

            String creationDate = this.getCreationDateObj(filename, workingPath)
            if (creationDate?.trim()) {
                infoChangesMap["creationDate"] = creationDate
            }
        }

        return infoChangesMap
    }

    def getCreationDateObj(def filename, String workingPath) {
        String infoDate = dsl.sh(script: "git log --pretty='format:%ad' --date=short '${workingPath}/${filename}' | tail -1", returnStdout: true)
        return infoDate
    }
}
