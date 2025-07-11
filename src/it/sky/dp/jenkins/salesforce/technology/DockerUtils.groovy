#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.technology

class DockerUtils implements Serializable {
    private def dsl

    DockerUtils(def dsl) {
        this.dsl = dsl
    }

    void buildImage(String imageName, String dockerfilePath) {
        dsl.sh("docker build -t ${imageName} ${dockerfilePath}")

        dsl.echo("Image ${imageName} built successfully")
    }

    void tagImage(String registryImageName, String registryImageTag, String imageName, String imageTag) {
        dsl.sh "docker tag ${registryImageName}:${registryImageTag} ${imageName}:${imageTag}"

        dsl.echo("Image ${imageName} tagged successfully")
    }

    void buildAndPushImage(String imageName, String registryRegion, String dockerfilePath, String accountId, String imageTag) {
        dsl.sh "aws ecr get-login-password --region ${registryRegion} | docker login --username AWS --password-stdin ${accountId}.dkr.ecr.${registryRegion}.amazonaws.com"

        dsl.dir(dockerfilePath) {
            dsl.sh "docker build -t ${imageName}:${imageTag} . --network=host"

            dsl.sh "docker push ${imageName}:${imageTag}"
        }

        dsl.echo("Image ${imageName}:${imageTag} pushed successfully")
    }

    void pullImage(String imageName, String accountId, String registryRegion, String imageTag) {
        dsl.sh "aws ecr get-login-password --region ${registryRegion} | docker login --username AWS --password-stdin ${accountId}.dkr.ecr.${registryRegion}.amazonaws.com"

        dsl.sh "docker pull ${imageName}:${imageTag}"
        
        dsl.echo("Image ${imageName}:${imageTag} pulled successfully")
    }
}
