package it.sky.dp.jenkins.models

class GitCredentials implements Serializable {

    private final String username
    private final String password

    GitCredentials(username, password) {
      this.username = username
      this.password = password
    }


}