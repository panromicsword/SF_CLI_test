#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.process

import it.sky.dp.jenkins.salesforce.common.Configuration

import static it.sky.dp.jenkins.salesforce.Constants.*

class SalesforceRetentionProcess extends AbstractProcess implements Serializable {

    SalesforceRetentionProcess(def dsl) {
        super(dsl)
    }

    @Override
    void checkParameters() {
        super.checkParam(MAP_KEY_DRY_RUN)
        super.checkParam(MAP_KEY_BRANCHES_TO_EXCLUDE)
        super.checkParam(MAP_KEY_TAGS_TO_EXCLUDE)
    }

    @Override
    void initProcessVariables() {
        Configuration cfg = Configuration.getInstance()
        cfg.addEntryToMap(MAP_KEY_BRANCH_NAME, "master", true)
    }

    def getEnvironmentTagsToKeep(def environmentList, String filterSeed) {
        def tagEnvironments = []

        environmentList.each { name ->
            String filter = name + filterSeed

            try {
                String envTag = gitUtils.getLastTagMatch(filter)

                if (envTag?.trim()) {
                    tagEnvironments.add(envTag)
                }
            } catch (Exception e) {
                dsl.echo(e.getMessage())
            }
        }

        return tagEnvironments
    }

    void collectionsElements() {
        Configuration cfg = Configuration.getInstance()

        // load Salesforce Configs file
        super.loadConfigs()

        //BRANCH
        //otteniamo elenco di branch mergiati e non mergiati
        dsl.echo "------------------ BRANCHES COLLECTION -----------------------------"
        this.collectBranches()

        //TAG
        // funzione per ottenere ultimi tag per ambiente
        dsl.echo "------------------ TAGS COLLECTION -----------------------------"
        this.collectTags()

        def mergedBranchesToDelete = cfg.getMapValue(MAP_KEY_MERGED_BRANCHES_TO_DELETE)
        dsl.echo "merged branches to delete: ${mergedBranchesToDelete}"

        def notMergedBranchesToDelete = cfg.getMapValue(MAP_KEY_NOT_MERGED_BRANCHES_TO_DELETE)
        def tagsToDelete = cfg.getMapValue(MAP_KEY_TAGS_TO_DELETE)

        String deleteMessage = "-------- Following elements that will be deleted --------\n\n"

        if (mergedBranchesToDelete.size() > 0) {
            String mergedBranchMessage = "\t" + mergedBranchesToDelete.join("\n\t")
            deleteMessage += "Merged Branches to delete:\n" + mergedBranchMessage + "\n\n"
        } else {
            deleteMessage += "No merged Branches to delete" + "\n\n"
        }

        if (notMergedBranchesToDelete.size() > 0) {
            String notMergedBranchMessage = "\t" + notMergedBranchesToDelete.join("\n\t")
            deleteMessage += "Not Merged Branches to delete:\n" + notMergedBranchMessage + "\n\n"
        } else {
            deleteMessage += "No unmerged Branches to delete" + "\n\n"
        }

        if (tagsToDelete.size() > 0) {
            String tagMessage = "\t" + tagsToDelete.join("\n\t")
            deleteMessage += "Tags to delete:\n" + tagMessage + "\n\n"
        } else {
            deleteMessage += "No Tags to delete" + "\n\n"
        }

        deleteMessage += "---------------------------------------------------------------------"
        dsl.echo(deleteMessage)
    }

    void confirmDeletion() {
        def inputParams = dsl.input message: "Please confirm the intention to delete objects (see the log above or about the previous stage)", ok: 'CONFIRM OBJECTS DELETION'
    }

    void deleteElements() {
        Configuration cfg = Configuration.getInstance()
        def mergedBranchesToDelete = cfg.getMapValue(MAP_KEY_MERGED_BRANCHES_TO_DELETE)
        def notMergedBranchesToDelete = cfg.getMapValue(MAP_KEY_NOT_MERGED_BRANCHES_TO_DELETE)
        def tagsToDelete = cfg.getMapValue(MAP_KEY_TAGS_TO_DELETE)

        gitUtils.deleteRemoteBranches(mergedBranchesToDelete)
        gitUtils.deleteRemoteBranches(notMergedBranchesToDelete)
        gitUtils.deleteRemoteTags(tagsToDelete)
    }

    void collectBranches() {
        Configuration cfg = Configuration.getInstance()
        def mergedBranches = gitUtils.collectBranches(true, "--merged")
        dsl.echo "Branches merged: ${mergedBranches}"

        def notMergedBranches = gitUtils.collectBranches(true, "--no-merged")
        dsl.echo "Branches not merged: ${notMergedBranches}"

        //escludiamo i branch esclusi dai parametri d'entrata
        def branchesToKeep = []
        branchesToKeep.add("origin/master")

        if (cfg.getMapValue(MAP_KEY_BRANCHES_TO_EXCLUDE)?.trim()) {
            def remoteBranchesPrefix = "origin/"

            def inputBranches = cfg.getMapValue(MAP_KEY_BRANCHES_TO_EXCLUDE).split("\n").collect { it.trim() }
            for (branch in inputBranches) {
                String remoteBranch = branch
                if (!remoteBranch.startsWith(remoteBranchesPrefix)) {
                    remoteBranch = remoteBranchesPrefix + branch
                }
                branchesToKeep.add(remoteBranch)
            }
        }
        dsl.echo "Branches to keep: ${branchesToKeep}"

        def mergedBranchesNoInput = mergedBranches - branchesToKeep
        dsl.echo "Branches merged to be checked: ${mergedBranchesNoInput}"
        def notMergedBranchesNoInput = notMergedBranches - branchesToKeep
        dsl.echo "Branches not merged to be checked: ${notMergedBranchesNoInput}"

        // filtriamo per ultima modifica
        def mergedBranchesToDelete = gitUtils.lastUpdateBranches(mergedBranchesNoInput, "2 month ago")
        dsl.echo "Branch merged to delete: ${mergedBranchesToDelete}"
        def notMergedBranchesToDelete = gitUtils.lastUpdateBranches(notMergedBranchesNoInput, "2 month ago")
        dsl.echo "Branch not merged to delete: ${notMergedBranchesToDelete}"

        cfg.addEntryToMap(MAP_KEY_MERGED_BRANCHES_TO_DELETE, mergedBranchesToDelete, false)
        cfg.addEntryToMap(MAP_KEY_NOT_MERGED_BRANCHES_TO_DELETE, notMergedBranchesToDelete, false)
    }

    void collectTags() {
        Configuration cfg = Configuration.getInstance()
        def testEnvironments = cfg.getMapValue(MAP_KEY_TEST_ENVIRONMENTS)

        def envSlfcTagsToKeep = this.getEnvironmentTagsToKeep(testEnvironments, "_*_SLFC")
        dsl.echo "Salesforce Environment tags to keep: ${envSlfcTagsToKeep}"
        boolean vlocityEnabled = cfg.getMapValue(MAP_KEY_VLOCITY_ENABLED)
        def envVlTagsToKeep = []
        if (vlocityEnabled) {
            envVlTagsToKeep = this.getEnvironmentTagsToKeep(testEnvironments, "_*_VL")
            dsl.echo "Vlocity Environment tags to keep: ${envVlTagsToKeep}"
        }

        //funzione per ottenere tutti i tag dell'utente
        def tagsToKeep = []

        if (cfg.getMapValue(MAP_KEY_TAGS_TO_EXCLUDE)?.trim()) {
            def inputTags = cfg.getMapValue(MAP_KEY_TAGS_TO_EXCLUDE).split("\n")
            tagsToKeep.addAll(inputTags)
        }

        //lista totale di tag da escludere per ambiente e parametri d'entrata
        def totalTagToKeep = []
        totalTagToKeep = tagsToKeep + envVlTagsToKeep + envSlfcTagsToKeep
        dsl.echo "Total tag to keep: ${totalTagToKeep}"

        def tagsInfoStr = gitUtils.getTagsInfo()
        def tagsToRemove = []

        // prefix environment list dependent
        String prefix = ""
        testEnvironments.each {
            prefix += "|${it}"
        }
        prefix = prefix.substring(1)

        // dynamic compiled regex
        def dynamicPattern = /^(/ + prefix + /)_(\d{14})_(SLFC|VL)$/

        if (tagsInfoStr?.trim()) {
            def tagInfoArr = tagsInfoStr.split("\n")

            tagInfoArr.each { tagInfo ->
                def tagArr = tagInfo.split("\\|")
                def tagNames = tagArr[1].split(",").findAll { it.startsWith("tag: ") }.collect { it.replace("tag:", "").trim() }
                tagNames.each { tag ->
                    // regex match
                    boolean tagMatch = (tag ==~ ~dynamicPattern)
                    if (tagMatch) {
                        String tagEnv = tag.substring(0, tag.indexOf('_'))
                        if (!(tag in totalTagToKeep) && (tagEnv in testEnvironments) && this.isTagCreatedAfterDate(tagArr[0], 2)) {
                            tagsToRemove.add(tag)
                        }
                    }
                }
            }
        } else {
            dsl.echo("No tags were found for branch master")
        }

        dsl.echo "Tags to delete: ${tagsToRemove}"
        cfg.addEntryToMap(MAP_KEY_TAGS_TO_DELETE, tagsToRemove, false)
    }

    private boolean isTagCreatedAfterDate(String creationDate, int numberOfMonths) {
        boolean output = true
        try {
            // deprecated
            Date date2MonthAgo = new Date()
            date2MonthAgo.setMonth(date2MonthAgo.getMonth() - numberOfMonths)

            Date tagDateCreation = new Date(creationDate)

            if (tagDateCreation.compareTo(date2MonthAgo) > 0) {
                output = false
            }
        } catch (Exception e) {
            dsl.echo("Error in isTagCreatedAfterDate (creationDate: \"${creationDate}\", numberOfMonths: \"${numberOfMonths.toString()}\"): " + e.getMessage())
            output = false
        }
        return output
    }

    @Override
    protected String getSlackMessageFirstPart(String buildResult) {
        String slackMessage = super.getSlackMessageFirstPart(buildResult)

        if ("SUCCESS".equals(buildResult.toUpperCase())) {
            slackMessage += "All objects confirmed have been deleted"
        } else if ("FAILURE".equals(buildResult.toUpperCase())) {
            slackMessage += "An error occurs during the deletion. Please check the Jenkins console"
        }

        return slackMessage
    }

}
