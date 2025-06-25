#!/usr/bin/env groovy
@Library(value = "jenkins-ci-library@acn_salesforce_kubernetes_pipelines", changelog=false)

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.common.Utils
import it.sky.dp.jenkins.salesforce.process.SalesforceDockerBuilderProcess
import it.sky.dp.jenkins.slack.SlackInfo
import it.sky.dp.jenkins.slack.SlackNotifier

import static it.sky.dp.jenkins.salesforce.Constants.*

Configuration cfg = Configuration.getInstance()
cfg.setDsl(this)

SalesforceDockerBuilderProcess process = new SalesforceDockerBuilderProcess(this)
SlackNotifier slackNotifier

/* Parametri pipeline (NOME - TIPOLOGIA - VALORI DEFAULT/CHOICE )
Parametro  ;Tipo  ;Valori;Default;Descrizione
BRANCH_NAME;String;      ;       ;TARGET_BRANCH_NAME: the name of the target branch of the pull request
*/

pipeline {
    agent { kubernetes(k8sAgent(cloud: 'deploy', podTemplate: 'docker-awscli2', serviceAccount: "jenkins-cross-deploy-sa")) }
    options {
        skipDefaultCheckout()
    }

    environment {
        PROJECT_NAME = 'adv_slsl'
        DOCKER_RUN_PARAMS = "-u root"
        SLACK_URL = "https://sky.slack.com/services/hooks/jenkins-ci/"
        SLACK_DEFAULT_CHANNEL = "ita-adv"
        PROJECT_URL = "https://github.com/sky-uk/adv_slsl/"
    }

    stages {
        stage("Cleanup workspace") {
            steps {
                container('docker-awscli2'){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)
                    }

                    cleanWs()
                }
            }
        }

        stage("Init variables") {
            steps {
                container('docker-awscli2'){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        SlackInfo slackInfo = new SlackInfo(SLACK_URL, SLACK_DEFAULT_CHANNEL, SLACK_DEFAULT_CHANNEL)
                        slackNotifier = new SlackNotifier(this, env, currentBuild, slackInfo)

                        Utils util = new Utils(this)
                        String buildUser = util.getBuildUser()
                        cfg.addEntryToMap(MAP_KEY_BUILD_USER_ID, buildUser, true)

                        String slackMessage = ":rocket:\n" +
                                "Build parameters:\n" +
                                "------------------------\n" +
                                "${MAP_KEY_BRANCH_NAME}: ${params.BRANCH_NAME}\n" +
                                "BUILD_USER_ID: ${buildUser}\n" +
                                "------------------------\n" +
                                "<${env.RUN_DISPLAY_URL}|${MESSAGE_OPEN_BLUE_OCEAN}>"
                        echo("slackMessage-> ${slackMessage}")

                        slackNotifier.notifyBuildStarted(slackMessage)

                        // set input variables to config map
                        cfg.addEntryToMap(MAP_KEY_BRANCH_NAME, params.BRANCH_NAME, true)

                        process.initVariables()
                    }
                }
            }
        }

        stage("Checkout source") {
            steps {
                container('docker-awscli2'){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.checkoutSources()
                    }
                }
            }
        }

        stage("Load Configs") {
            steps {
                container('docker-awscli2'){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.loadConfigs()
                    }
                }
            }
        }

        stage("Build Docker image") {
            when {
                expression { !cfg.getMapValue("DOCKER_BUILD_SKIP") }
            }
            steps {
                container('docker-awscli2'){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.buildAndPushDockerImage()
                    }
                }
            }
        }

        stage("Pull Docker image") {
            when {
                expression { !cfg.getMapValue("DOCKER_PULL_SKIP") }
            }
            steps {
                container('docker-awscli2'){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.pullDockerImage()
                    }
                }
            }
        }

        /*stage("Test Docker image") {
            steps {
                //container('docker-awscli2'){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)
                        String imageName = cfg.getMapValue(MAP_KEY_DOCKER_IMAGE_NAME)
                        String imageTag = cfg.getMapValue(MAP_KEY_DOCKER_IMAGE_TAG)
                        sh "docker run -it ${imageName}:${imageTag}"
                        sh "sfdx --help 2>/dev/null"

                        //String dockerImage = cfg.getMapValue(MAP_KEY_DOCKER_IMAGE_FULLNAME)
                        docker.image("${imageName}:${imageTag}").inside(DOCKER_RUN_PARAMS) {
                            sh "sfdx --help 2>/dev/null"
                        }
                    }
                //}
            }
        }*/
    }

    post {
        always {
            echo '### FINALLY'
            echo "Configuration->\n\n" + cfg.toString() + "\n"
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
