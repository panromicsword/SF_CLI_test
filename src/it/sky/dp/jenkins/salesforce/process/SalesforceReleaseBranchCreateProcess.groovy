#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.process

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.common.Utils
import it.sky.dp.jenkins.salesforce.technology.CsvSalesforceUtils

import static it.sky.dp.jenkins.salesforce.Constants.*

class SalesforceReleaseBranchCreateProcess extends AbstractSalesforcePRProcess implements Serializable {
    protected Utils utils
    protected CsvSalesforceUtils csvUtils

    SalesforceReleaseBranchCreateProcess(def dsl) {
        super(dsl)
        this.utils = new Utils(dsl)
        this.csvUtils = new CsvSalesforceUtils(dsl)
    }

    @Override
    void checkParameters() {
        super.checkParam(MAP_KEY_SOURCE_BRANCH_NAME)
        super.checkParam(MAP_KEY_BRANCH_NAME)
        super.checkParam(MAP_KEY_TARGET_BRANCH_NAME)
        super.checkParam(MAP_KEY_RELEASE_VERSION)
    }

    @Override
    void initProcessVariables() {
        Configuration cfg = Configuration.getInstance()
        String sourceBranch = cfg.getMapValue(MAP_KEY_SOURCE_BRANCH_NAME)
        String targetBranch = cfg.getMapValue(MAP_KEY_TARGET_BRANCH_NAME)
        String releaseVersion = cfg.getMapValue(MAP_KEY_RELEASE_VERSION)

        if (!releaseVersion.matches(/\d{4}\.(0?[1-9]|1[012])\.(0?[1-9]|[12][0-9]|3[01]).*/)) {
            dsl.error "Release version NOT acceptable \"${releaseVersion}\""
        }

        if (sourceBranch.trim().equals("")) {
            dsl.error("SOURCE_BRANCH needs to be set")
        }

        if (targetBranch.trim().equals("")) {
            dsl.error("TARGET_BRANCH needs to be set")
        } else {
            cfg.addEntryToMap(MAP_KEY_NEW_BRANCH_NAME, targetBranch, true)
        }
    }

    void createLocalBranch() {
        Configuration cfg = Configuration.getInstance()
        String releaseBranchName = cfg.getMapValue(MAP_KEY_NEW_BRANCH_NAME)
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)

        boolean releaseBranchNameExists = gitUtils.checkBranchExist(releaseBranchName, true)
        cfg.addEntryToMap(MAP_KEY_RELEASE_BRANCH_EXIST, releaseBranchNameExists, true)

        gitUtils.checkoutLocalBranch(releaseBranchName, releaseBranchNameExists, workingPath)

        // load Salesforce Configs file
        super.loadConfigs()

        if (!releaseBranchNameExists) {
            dsl.echo "The new release branch ${releaseBranchName} has been created"
        }
    }

    void creationReleaseFolder() {
        Configuration cfg = Configuration.getInstance()
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)
        String releaseVersion = cfg.getMapValue(MAP_KEY_RELEASE_VERSION)
        boolean releaseBranchNameExists = cfg.getMapValue(MAP_KEY_RELEASE_BRANCH_EXIST)
        def prodEnvironments = cfg.getMapValue(MAP_KEY_PROD_ENVIRONMENTS)
        def testEnvironments = cfg.getMapValue(MAP_KEY_TEST_ENVIRONMENTS)
        def envs = testEnvironments + prodEnvironments

        //esiste la cartella release?
        String releaseFolder = "${workingPath}/manual_procedures/${releaseVersion}"
        utils.ensureFolderExist(releaseFolder, releaseBranchNameExists, false)

        //esiste la cartella metdata?
        String metadataFolder = "${releaseFolder}/metadata"
        utils.ensureFolderExist(metadataFolder, releaseBranchNameExists, false)

        String[] metadata = ["labels", "objects", "profiles"]
        metadata.each { folder ->
            String objFolder = "${metadataFolder}/${folder}"
            utils.ensureFolderExist(objFolder, releaseBranchNameExists, true)
        }

        def slfcConfigs = dsl.readJSON file: "${workingPath}/devops/salesforce-configs.json"
        String[] vendor = slfcConfigs.vendors

        vendor.each { vend ->
            // esiste cartella dei vendor
            String vendorFolder = "${releaseFolder}/${vend}"
            utils.ensureFolderExist(vendorFolder, releaseBranchNameExists, false)
            //esiste file vendor
            String vendorFile = "${vendorFolder}/${releaseVersion}_Manual_Procedures_${vend}.csv"
            if (!dsl.fileExists(vendorFile)) {
                if (releaseBranchNameExists) {
                    dsl.echo "WARNING: the file \"${vendorFile}\" doesn't exist"
                }
                csvUtils.createManualproceduresCsv(vendorFile, envs)
            }
        }
    }

    void commitAndPush() {
        Configuration cfg = Configuration.getInstance()
        String releaseBranchName = cfg.getMapValue(MAP_KEY_NEW_BRANCH_NAME)
        boolean releaseBranchNameExists = cfg.getMapValue(MAP_KEY_RELEASE_BRANCH_EXIST)
        gitUtils.commitAndPushBranch(releaseBranchName, releaseBranchNameExists, "New Release Branch: ${releaseBranchName}")
    }
}
