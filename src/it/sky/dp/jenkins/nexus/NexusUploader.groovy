package it.sky.dp.jenkins.nexus

class NexusUploader {

    private final String nexusVersion = 'nexus3'
    private final String protocol = 'http'
    private String nexusCredentialsId = 'b4b1450f-81fb-4ef6-9911-ab028d562799'
    def steps
    def nexusUrl
    def repository

    NexusUploader(steps, nexusUrl, repository) {
        this.steps = steps
        this.nexusUrl = nexusUrl
        this.repository = repository
    }

    NexusUploader(steps, nexusUrl, repository, nexusCredentialsId) {
        this.steps = steps
        this.nexusUrl = nexusUrl
        this.repository = repository
        this.nexusCredentialsId = nexusCredentialsId
    }

    public void uploadArtifact(groupId, artifactId, version, classifier, file, type) {
        steps.step([
                $class       : 'NexusArtifactUploader',
                artifacts    : [[artifactId: artifactId,
                                 classifier: classifier,
                                 file      : file,
                                 type      : type]],
                credentialsId: "${nexusCredentialsId}",
                groupId      : groupId,
                nexusUrl     : "${nexusUrl}",
                nexusVersion : "${nexusVersion}",
                protocol     : "${protocol}",
                repository   : "${repository}",
                version      : version
        ])
    }
    
    public void uploadArtifact(groupId, artifactId, version, file, type) {
        steps.step([
                $class       : 'NexusArtifactUploader',
                artifacts    : [[artifactId: artifactId,
                                 classifier: "",
                                 file      : file,
                                 type      : type]],
                credentialsId: "${nexusCredentialsId}",
                groupId      : groupId,
                nexusUrl     : "${nexusUrl}",
                nexusVersion : "${nexusVersion}",
                protocol     : "${protocol}",
                repository   : "${repository}",
                version      : version
        ])
    }

    
    
    public void downloadArtifact(groupId, artifactId, version, classifier, targetFileName) {
        steps.step([
            $class: 'ArtifactResolver',
            artifacts: [[
                artifactId: artifactId, 
                classifier: classifier,
                groupId: groupId,
                targetFileName: targetFileName,
                version: version]],
            enableRepoLogging: false,
            failOnError: false
        ])
    }
    
    public void downloadArtifact(groupId, artifactId, extension, version, classifier, targetFileName) {
        steps.step([
            $class: 'ArtifactResolver',
            artifacts: [[
                artifactId: artifactId,
                extension: extension,
                classifier: classifier,
                groupId: groupId,
                targetFileName: targetFileName,
                version: version]],
            enableRepoLogging: false,
            failOnError: false
        ])
    }
    
    public void downloadArtifact(groupId, artifactId, version, targetFileName) {
        steps.step([
            $class: 'ArtifactResolver',
            artifacts: [[
                artifactId: artifactId, 
                classifier: "",
                groupId: groupId,
                targetFileName: targetFileName,
                version: version]],
            enableRepoLogging: false,
            failOnError: false
        ])
    }
    
    public void download(groupId, artifactId, extension, version, targetFileName) {
        steps.step([
            $class: 'ArtifactResolver',
            artifacts: [[
                artifactId: artifactId,
                extension: extension,
                classifier: "",
                groupId: groupId,
                targetFileName: targetFileName,
                version: version]],
            enableRepoLogging: false,
            failOnError: false
        ])
    }
    

    
}

