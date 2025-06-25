#!/usr/bin/env groovy
@Library(value = "jenkins-ci-library@acn_salesforce_kubernetes_pipelines", changelog=false)

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.common.Utils
import it.sky.dp.jenkins.salesforce.process.SalesforceDeployProcess
import it.sky.dp.jenkins.slack.SlackInfo
import it.sky.dp.jenkins.slack.SlackNotifier

import static it.sky.dp.jenkins.salesforce.Constants.*

Configuration cfg = Configuration.getInstance()
cfg.setDsl(this)

SalesforceDeployProcess process = new SalesforceDeployProcess(this)
SlackNotifier slackNotifier

def repositoryCicd = "SFDC_Arcadia"

/* Parametri pipeline (NOME - TIPOLOGIA - VALORI DEFAULT/CHOICE )
Parametro         ;Tipo   ;Valori                                                    ;Default          ;Descrizione
BRANCH_NAME       ;String ;                                                          ;FULL_2021.03.04  ;BRANCH_NAME: the name of the branch to deploy
START_HASH        ;String ;                                                          ;master           ;START_HASH: the starting hash for deploy (Optional)
END_HASH          ;String ;                                                          ;PRODRYRUN        ;END_HASH: the ending hash for deploy (Optional)
VALIDATE_ONLY     ;Boolean;                                                          ;TRUE             ;VALIDATE_ONLY: set 'true' to run only the validation
TARGET_ENVIRONMENT;Choice ;NONE,IT,ST,TST5,AM,UAT,ACNDEVOPS                          ;NONE             ;TARGET_ENVIRONMENT: the target environment where to validate/deploy
TEST_LEVEL        ;Choice ;RunSpecifiedTests,NoTestRun,RunLocalTests,RunAllTestsInOrg;RunSpecifiedTests;TEST_LEVEL: the test level to use during validate (and deploy in case of Production)
RELEASE_VERSION   ;String ;                                                          ;2021.03.04       ;RELEASE_VERSION: the name of the release in the SKY format (i.e. 2020.12.16)
SKIP_SCA          ;Boolean;                                                          ;TRUE             ;SKIP_SCA: set 'true' to skip static code analysis
QUICK_DEPLOY_ID   ;String ;                                                          ;                 ;QUICK_DEPLOY_ID: the deploy ID of a previous validated run (Optional)
*/

pipeline {
    agent { kubernetes(k8sAgent(cloud: 'deploy', podTemplate: 'salesforce-' + env.SALESFORCE_VERSION)) }
    options { 
        skipDefaultCheckout()
    }

    environment {
        PROJECT_NAME = 'SFDC_Arcadia'
        DOCKER_RUN_PARAMS = "-u root"
        SLACK_URL = "https://sky.slack.com/services/hooks/jenkins-ci/"
        SLACK_DEFAULT_CHANNEL = "ita-salesforce"
        PROJECT_URL = " https://github.com/sky-uk/SFDC_Arcadia/" 
    }

    stages {
        stage("Cleanup workspace") {
            steps {
                script {
                    cfg.setLastStage(env.STAGE_NAME)
                }

                cleanWs()
            }
        }

        stage("Init variables ") {
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        String slackChannel = SLACK_DEFAULT_CHANNEL
                        if (!"NONE".equals(params.TARGET_ENVIRONMENT)) {
                            slackChannel += "-" + params.TARGET_ENVIRONMENT
                        }
                        SlackInfo slackInfo = new SlackInfo(SLACK_URL, slackChannel.toLowerCase(), slackChannel.toLowerCase())
                        slackNotifier = new SlackNotifier(this, env, currentBuild, slackInfo)

                        Utils util = new Utils(this)
                        String buildUser = util.getBuildUser()
                        cfg.addEntryToMap(MAP_KEY_BUILD_USER_ID, buildUser, true)

                        String slackMessage = ":rocket:\n" +
                                "Build parameters:\n" +
                                "------------------------\n" +
                                "${MAP_KEY_BRANCH_NAME}: ${params.BRANCH_NAME}\n" +
                                "${MAP_KEY_START_HASH}: ${params.START_HASH}\n" +
                                "${MAP_KEY_END_HASH}: ${params.END_HASH}\n" +
                                "${MAP_KEY_VALIDATE_ONLY}: ${params.VALIDATE_ONLY}\n" +
                                "${MAP_KEY_TARGET_ENVIRONMENT}: ${params.TARGET_ENVIRONMENT}\n" +
                                "${MAP_KEY_TEST_LEVEL}: ${params.TEST_LEVEL}\n" +
                                "${MAP_KEY_RELEASE_VERSION}: ${params.RELEASE_VERSION}\n" +
                                "${MAP_KEY_SKIP_SCA}: ${params.SKIP_SCA}\n" +
                                "${MAP_KEY_QUICK_DEPLOY_ID}: ${params.QUICK_DEPLOY_ID}\n" +
                                "${MAP_KEY_SKIP_INFO_COMMIT}: ${params.SKIP_INFO_COMMIT}\n" +
                                "BUILD_USER_ID: ${buildUser}\n" +
                                "------------------------\n" +
                                "<${env.RUN_DISPLAY_URL}|${MESSAGE_OPEN_BLUE_OCEAN}>"
                        echo("slackMessage-> ${slackMessage}")

                        slackNotifier.notifyBuildStarted(slackMessage)

                        // set input variables to config map
                        cfg.addEntryToMap(MAP_KEY_BRANCH_NAME, params.BRANCH_NAME, true)
                        cfg.addEntryToMap(MAP_KEY_START_HASH, params.START_HASH, false)
                        cfg.addEntryToMap(MAP_KEY_END_HASH, params.END_HASH, false)
                        cfg.addEntryToMap(MAP_KEY_VALIDATE_ONLY, params.VALIDATE_ONLY, true)
                        cfg.addEntryToMap(MAP_KEY_TARGET_ENVIRONMENT, params.TARGET_ENVIRONMENT, true)
                        cfg.addEntryToMap(MAP_KEY_TEST_LEVEL, params.TEST_LEVEL, true)
                        cfg.addEntryToMap(MAP_KEY_RELEASE_VERSION, params.RELEASE_VERSION, true)
                        cfg.addEntryToMap(MAP_KEY_SKIP_SCA, params.SKIP_SCA, true)
                        cfg.addEntryToMap(MAP_KEY_QUICK_DEPLOY_ID, params.QUICK_DEPLOY_ID, false)
                        cfg.addEntryToMap(MAP_KEY_SKIP_INFO_COMMIT, params.SKIP_INFO_COMMIT, true)

                        if (params.QUICK_DEPLOY_ID?.trim()) {
                            slackNotifier.notifyBuildWarning(":warning: WARNING: Quick deploy has been passed, some step will be skipped")
                        } else {
                            if (TEST_RUN_NONE.equals(params.TEST_LEVEL)) {
                                slackNotifier.notifyBuildWarning(":warning: WARNING: No test run has been selected by the user")
                            }
                        }

                        process.initVariables()

                    }
                }
            }
        }

        stage("Checkout source ") {
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.checkoutSources()

                    }
                }
            }
        }

        stage("Pre Deploy") {
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)
                        process.preDeploy()
                    }
                }
            }
        }

        stage("Convert source") {
            when {
                expression { cfg.getMapValue("DELTA_FORCEAPP_EXISTS") && currentBuild.currentResult == 'SUCCESS' && !cfg.getMapValue("IS_QUICK_DEPLOY") }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.convertSources()

                    }
                }
            }
        }

        stage("Pre-MP Check") {
            when {
                expression { cfg.getMapValue("DELTA_PRE_MANUAL_PROCEDURES_EXISTS") && currentBuild.currentResult == 'SUCCESS' && !cfg.getMapValue("IS_QUICK_DEPLOY") }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        slackNotifier.notifyBuildWarning(":question: Please perform all manual procedures for the step PRE " + params.TARGET_ENVIRONMENT)

                        process.preMPCheck()
                    }
                }
            }
        }

        stage("Validate") {
            when {
                expression { cfg.getMapValue("DELTA_FORCEAPP_EXISTS") && currentBuild.currentResult == 'SUCCESS' && !cfg.getMapValue("IS_QUICK_DEPLOY") }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        script {
                            cfg.setLastStage(env.STAGE_NAME)

                            process.validate()
                            
                        }
                    }
                }
            }
        }

        stage("Quality") {
            when {
                expression { (cfg.getMapValue("DELTA_FORCEAPP_EXISTS") || cfg.getMapValue("VLOCITY_DELTA_EXISTS") || cfg.getMapValue("DELTA_MANUAL_PROCEDURES_EXISTS")) && !cfg.getMapValue("IS_QUICK_DEPLOY") }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.quality()

                    }
                }
            }
        }

        stage("Store") {
            when {
                expression { !cfg.getMapValue("IS_QUICK_DEPLOY") }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.storeArtifact()
                    }
                }
            }
        }

        stage("Deploy") {
            when {
                expression { !params.VALIDATE_ONLY && (cfg.getMapValue("DELTA_FORCEAPP_EXISTS") || cfg.getMapValue("IS_QUICK_DEPLOY")) && currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.deploy()

                    }
                }
            }
        }

        stage("Tag source") {
            when {
                expression { !params.VALIDATE_ONLY && (cfg.getMapValue("DELTA_FORCEAPP_EXISTS") || cfg.getMapValue("DELTA_MANUAL_PROCEDURES_EXISTS") || cfg.getMapValue("IS_QUICK_DEPLOY")) && currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)


                        process.tagSource()
                    }
                }
            }
        }

        stage("Deploy VLT") {
            when {
                expression { !params.VALIDATE_ONLY && cfg.getMapValue("VLOCITY_DELTA_EXISTS") && currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.deployVlocity()

                    }
                }
            }
        }

        stage("Tag source VLT") {
            when {
                expression { !params.VALIDATE_ONLY && cfg.getMapValue("VLOCITY_DELTA_EXISTS") && currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.tagSourceVlocity()
                    }
                }
            }
        }

        stage("Post-MP Check") {
            when {
                expression { !params.VALIDATE_ONLY && cfg.getMapValue("DELTA_POST_MANUAL_PROCEDURES_EXISTS") && currentBuild.currentResult == 'SUCCESS' && !cfg.getMapValue("IS_QUICK_DEPLOY") }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        slackNotifier.notifyBuildWarning(":question: Please perform all manual procedures for the step POST " + params.TARGET_ENVIRONMENT)

                        process.postMPCheck()
                    }
                }
            }
        }
    }

    post {
        always {
            echo '### FINALLY'
            echo "Configuration->\n\n" + cfg.toString() + "\n"
            script {
                process.updateElasticSearch()
            }
        }
        success {
            echo '### SUCCESS'
            script {
                slackNotifier.notifyBuildSuccess(process.getSuccessSlackMessage())
            }
        }
        unstable {
            echo '### UNSTABLE'
            script {
                slackNotifier.notifyBuildWarning(process.getUnstableSlackMessage())
            }
        }
        failure {
            echo '### FAILURE'
            script {
                slackNotifier.notifyBuildFailed(process.getFailureSlackMessage())
            }
        }
        aborted {
            echo '### ABORTED'
            script {
                slackNotifier.notifyBuildFailed(process.getAbortSlackMessage())
            }
        }
    }
}
