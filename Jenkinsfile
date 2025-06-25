pipeline {
  agent {
    kubernetes {
      yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: jnlp
      image: jenkins/inbound-agent:latest
      args: ['\$(JENKINS_SECRET)', '\$(JENKINS_NAME)']
    - name: sfcli
      image: selva015/sfcli
      command:
        - cat
      tty: true
"""
    }
  }

  stages {
    stage('Verify SF CLI') {
      steps {
        container('sfcli') {
          sh 'sf --version'
          sh 'sf org list'
        }
      }
    }
  }
}
