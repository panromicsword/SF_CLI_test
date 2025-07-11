#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.process

import it.sky.dp.jenkins.salesforce.common.Configuration

import static it.sky.dp.jenkins.salesforce.Constants.*

class SalesforceDockerBuilderProcess extends AbstractProcess implements Serializable {

    SalesforceDockerBuilderProcess(def dsl) {
        super(dsl)
    }

    @Override
    void checkParameters() {
        super.checkParam(MAP_KEY_BRANCH_NAME)
    }

    @Override
    void checkoutSources() {
        Configuration cfg = Configuration.getInstance()
        String repositoryUrl = cfg.getMapValue(MAP_KEY_REPOSITORY_URL)
        String repositoryCredentialId = cfg.getMapValue(MAP_KEY_REPOSITORY_CREDENTIAL_ID)
        String branchName = cfg.getMapValue(MAP_KEY_BRANCH_NAME)
        String workingPath = cfg.getMapValue(MAP_KEY_WORKING_PATH)

        gitUtils.lightCheckoutBranch(repositoryUrl, branchName, repositoryCredentialId, workingPath)
        utils.checkDiskSpace()
    }

    void buildAndPushDockerImage() {
        Configuration cfg = Configuration.getInstance()
        String dockerfilePath = cfg.getMapValue(MAP_KEY_DOCKERFILE_PATH)
        String imageFullName = cfg.getMapValue(MAP_KEY_DOCKER_REGISTRY_ENDPOINT)
        String registryEndpoint = cfg.getMapValue(MAP_KEY_DOCKER_REGISTRY_ENDPOINT)
        String registryRegion = cfg.getMapValue(MAP_KEY_DOCKER_REGISTRY_REGION)
        String accountId = cfg.getMapValue(MAP_KEY_DOCKER_REGISTRY_ACCOUNT_ID)
        String imageTag = cfg.getMapValue(MAP_KEY_DOCKER_IMAGE_TAG)

        dockerUtils.buildAndPushImage(imageFullName, registryRegion, dockerfilePath, accountId, imageTag)
        utils.checkDiskSpace()
    }

}