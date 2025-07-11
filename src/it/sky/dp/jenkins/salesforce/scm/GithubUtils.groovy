#!/usr/bin/groovy
package it.sky.dp.jenkins.salesforce.scm

import it.sky.dp.jenkins.salesforce.common.Utils

class GithubUtils implements Serializable {
    private def dsl

    private final String MAP_KEY_GITHUB_PROTOCOL = "GITHUB_PROTOCOL"
    private final String MAP_KEY_GITHUB_HOST = "GITHUB_HOST"
    private final String MAP_KEY_GITHUB_ORGANIZATION = "GITHUB_ORGANIZATION"
    private final String MAP_KEY_GITHUB_REPOSITORY_NAME = "GITHUB_REPOSITORY_NAME"

    private final String PR_API_ENDPOINT = "{_protocol_}://api.github.com/repos/{_organization_}/{_repositoryName_}/pulls"
    private final String BRANCH_API_ENDPOINT = "{_protocol_}://api.github.com/repos/{_organization_}/{_repositoryName_}/branches"

    GithubUtils(def dsl) {
        this.dsl = dsl
    }

    private def getRepositoryInfos(String repositoryUrl) {
        def repoInfo = [:]

        int protocolIndex = repositoryUrl.indexOf("://")
        assert protocolIndex > -1

        String gitProtocol = repositoryUrl.substring(0, protocolIndex)
        repoInfo.put(MAP_KEY_GITHUB_PROTOCOL, gitProtocol)

        String gitEndpoint = repositoryUrl.substring(protocolIndex + 3)
        String[] endpointArr = gitEndpoint.split("/")
        if (endpointArr.length == 3) {
            repoInfo.put(MAP_KEY_GITHUB_HOST, endpointArr[0])
            repoInfo.put(MAP_KEY_GITHUB_ORGANIZATION, endpointArr[1])
            repoInfo.put(MAP_KEY_GITHUB_REPOSITORY_NAME, endpointArr[2])
        } else {
            dsl.error("Issue during retrieve of Pull Request Endpoint for endpoint ${gitEndpoint}")
        }

        return repoInfo
    }

    private String getAPIEndpoint(String repositoryUrl, String apiUrl) {
        def repoInfo = this.getRepositoryInfos(repositoryUrl)

        assert repoInfo.get(MAP_KEY_GITHUB_ORGANIZATION)
        assert repoInfo.get(MAP_KEY_GITHUB_REPOSITORY_NAME)

        String finalApiUrl = apiUrl
        finalApiUrl = finalApiUrl.replace("{_protocol_}", repoInfo.get(MAP_KEY_GITHUB_PROTOCOL))
        finalApiUrl = finalApiUrl.replace("{_organization_}", repoInfo.get(MAP_KEY_GITHUB_ORGANIZATION))
        finalApiUrl = finalApiUrl.replace("{_repositoryName_}", repoInfo.get(MAP_KEY_GITHUB_REPOSITORY_NAME))

        return finalApiUrl
    }

    private String getPullRequestEndpoint(String repositoryUrl) {
        return this.getAPIEndpoint(repositoryUrl, PR_API_ENDPOINT)
    }

    private String getBranchEndpoint(String repositoryUrl) {
        return this.getAPIEndpoint(repositoryUrl, BRANCH_API_ENDPOINT)
    }

    boolean checkPullRequestExists(String repositoryUrl, String sourceBranch, String targetBranch, String credentialId) {
        def prMap = this.getResponsePullRequest(repositoryUrl, sourceBranch, targetBranch, credentialId)

        return !prMap.isEmpty()
    }

    Map getPullRequests(String repositoryUrl, String sourceBranch, String targetBranch, String credentialId) {
        return this.getResponsePullRequest(repositoryUrl, sourceBranch, targetBranch, credentialId)
    }

    def createPullRequest(String repositoryUrl, String sourceBranch, String targetBranch, String credentialId, String prDescription) {
        def jsonResponse
        String requestEndpoint = this.getPullRequestEndpoint(repositoryUrl)

        String requestBodyStr = """{
    \"title\":\"AUTOM-${sourceBranch}-${targetBranch}-${dsl.env.BUILD_NUMBER}\",
    \"head\":\"${sourceBranch}\",
    \"base\":\"${targetBranch}\",
    \"body\":\"${prDescription.replace('\n', '\\n')}\"
}"""
        // encode invalid chars
        requestBodyStr = Utils.encodeJson(requestBodyStr)

        dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialId, usernameVariable: "USERNAME", passwordVariable: "TOKEN"]]) {
            def response = dsl.httpRequest consoleLogResponseBody: false,
                    contentType: 'APPLICATION_JSON',
                    customHeaders: [[maskValue: false, name: 'Accept', value: 'application/vnd.github.v3+json'],
                                    [maskValue: true, name: 'Authorization', value: 'token ' + dsl.token]],
                    httpMode: 'POST',
                    requestBody: requestBodyStr,
                    url: requestEndpoint,
                    wrapAsMultipart: false,
                    validResponseCodes: '100:599'

            // for response code see https://docs.github.com/en/rest/reference/pulls#create-a-pull-request
            switch (response.status) {
                case "201":
                    dsl.echo("Status : 201 Created")
                    jsonResponse = response.content
                    break

                case "422":
                    dsl.echo("Status: 422 Unprocessable Entity" + "\n response content: " + response.content)
                    break

                default:
                    dsl.error("Unmanaged response status: " + response.status + "\n response content: " + response.content)
            }
        }

        return jsonResponse
    }

    private Map getResponsePullRequest(String repositoryUrl, String sourceBranch, String targetBranch, String credentialId) {
        String prAPIEndpoint = this.getPullRequestEndpoint(repositoryUrl)
        String requestEndpoint = prAPIEndpoint
        def filteredPr = [:]

        def repoInfo = this.getRepositoryInfos(repositoryUrl)
        assert repoInfo.get(MAP_KEY_GITHUB_ORGANIZATION)

        if (sourceBranch?.trim() || targetBranch?.trim()) {
            requestEndpoint += "?"

            if (sourceBranch?.trim()) {
                requestEndpoint += (requestEndpoint.endsWith("?")) ? "" : "&"
                requestEndpoint += "head=${repoInfo.get(MAP_KEY_GITHUB_ORGANIZATION)}:${sourceBranch}"
            }

            if (targetBranch?.trim()) {
                requestEndpoint += (requestEndpoint.endsWith("?")) ? "" : "&"
                requestEndpoint += "base=${targetBranch}"
            }
        }

        dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialId, usernameVariable: "USERNAME", passwordVariable: "TOKEN"]]) {
            def response = this.dsl.httpRequest url: requestEndpoint,
                    customHeaders: [[maskValue: false, name: 'Accept', value: 'application/vnd.github.v3+json'],
                                    [maskValue: true, name: 'Authorization', value: 'token ' + dsl.TOKEN]],
                    consoleLogResponseBody: false,
                    contentType: "APPLICATION_JSON",
                    validResponseCodes: '100:599',
                    httpMode: "GET"

            // for response code see https://docs.github.com/en/rest/reference/pulls#get-a-pull-request
            switch (response.status) {
                case "200":
                    dsl.echo("Status: 200 OK")
                    break

                case "304":
                    dsl.echo("Status : 304 Not Modified")
                    break

                default:
                    dsl.error("Unmanaged response status: " + response.status + "\n response content: " + response.content)
            }

            if (response.content?.trim()) {
                def jsonObj = this.dsl.readJSON text: response.content

                jsonObj.each { propVal ->
                    def pr = [:]
                    pr.put("user", "${propVal.user.login}")
                    pr.put("Title job", "${propVal.title}")
                    pr.put("Source_Branch", "${propVal.head.ref}")
                    pr.put("Target_Branch", "${propVal.base.ref}")
                    pr.put("Pull_number", "${propVal.number}")
                    pr.put("Status", "${propVal.state}")
                    pr.put("Created at", "${propVal.created_at}")
                    pr.put("Close at", "${propVal.close_at}")
                    pr.put("Merge at", "${propVal.merge_at}")
                    pr.put("Merge_commit_sha", "${propVal.merge_commit_sha}")
                    filteredPr.put(propVal.number, pr)
                }

                if (filteredPr.size() > 0) {
                    dsl.echo "filteredPr ->\n" + filteredPr
                }
            }
        }

        return filteredPr
    }

    def updatePR(String repositoryUrl, String sourceBranch, String targetBranch, String credentialId, String prDescription) {
        def jsonResponse
        String requestEndpoint = this.getPullRequestEndpoint(repositoryUrl)

        def prMap = this.getResponsePullRequest(repositoryUrl, sourceBranch, targetBranch, credentialId)

        String pullRequestNumber = prMap.keySet()[0]
        requestEndpoint += "/${pullRequestNumber}"

        String requestBodyStr = """{
    \"title\":\"AUTOM-${sourceBranch}-${targetBranch}-${dsl.env.BUILD_NUMBER}\",
    \"body\":\"${prDescription.replace('\n', '\\n')}\"
}"""
        // encode invalid chars
        requestBodyStr = Utils.encodeJson(requestBodyStr)

        dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialId, usernameVariable: "USERNAME", passwordVariable: "TOKEN"]]) {
            def response = dsl.httpRequest consoleLogResponseBody: false,
                    contentType: 'APPLICATION_JSON',
                    customHeaders: [[maskValue: false, name: 'Accept', value: 'application/vnd.github.v3+json'],
                                    [maskValue: true, name: 'Authorization', value: 'token ' + dsl.token]],
                    httpMode: 'PATCH',
                    requestBody: requestBodyStr,
                    url: requestEndpoint,
                    wrapAsMultipart: false,
                    validResponseCodes: '100:599'

            switch (response.status) {
                case "200":
                    dsl.echo("Status : 200 OK")
                    jsonResponse = response.content
                    break

                case "422":
                    dsl.echo("Status: 422 Unprocessable Entity" + "\n response content: " + response.content)
                    break

                default:
                    dsl.error("Unmanaged response status: " + response.status + "\n response content: " + response.content)
            }

            if (response.content?.trim()) {
                dsl.echo("The pull request has been updated")
            }
        }

        return jsonResponse
    }

    boolean mergePullRequest(String repositoryUrl, String pullRequestNumber, String credentialId) {
        String prAPIEndpoint = this.getPullRequestEndpoint(repositoryUrl)
        boolean merged = false

        if (pullRequestNumber?.trim()) {
            prAPIEndpoint += "/${pullRequestNumber}/merge"
        } else {
            dsl.error("Pull request number is null or empty")
        }

        dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialId, usernameVariable: "USERNAME", passwordVariable: "TOKEN"]]) {
            def response = this.dsl.httpRequest url: prAPIEndpoint,
                    customHeaders: [[maskValue: false, name: 'Accept', value: 'application/vnd.github.v3+json'],
                                    [maskValue: true, name: 'Authorization', value: 'token ' + dsl.TOKEN]],
                    consoleLogResponseBody: false,
                    contentType: 'APPLICATION_JSON',
                    validResponseCodes: '100:599',
                    httpMode: 'PUT',
                    wrapAsMultipart: false

            switch (response.status) {
                case "200":
                    dsl.echo("Status : 200 OK")
                    break

                case "422":
                    dsl.echo("Status: 422 Unprocessable Entity" + "\n response content: " + response.content)
                    break

                default:
                    dsl.error("Unmanaged response status: " + response.status + "\n response content: " + response.content)
            }

            if (response.content?.trim()) {
                def jsonObj = this.dsl.readJSON text: response.content
                merged = jsonObj.merged
            }
        }

        return merged
    }

    boolean isPullRequestMergeable(String repositoryUrl, String pullRequestNumber, String credentialId) {
        boolean mergeable = false
        String prAPIEndpoint = this.getPullRequestEndpoint(repositoryUrl)

        if (pullRequestNumber?.trim()) {
            prAPIEndpoint += "/${pullRequestNumber}"
        } else {
            dsl.error("Pull request number is null or empty")
        }

        dsl.withCredentials([[$class: "UsernamePasswordMultiBinding", credentialsId: credentialId, usernameVariable: "USERNAME", passwordVariable: "TOKEN"]]) {
            def response = this.dsl.httpRequest url: prAPIEndpoint,
                    customHeaders: [[maskValue: false, name: 'Accept', value: 'application/vnd.github.v3+json'],
                                    [maskValue: true, name: 'Authorization', value: 'token ' + dsl.TOKEN]],
                    consoleLogResponseBody: false,
                    contentType: 'APPLICATION_JSON',
                    validResponseCodes: '100:599',
                    httpMode: 'GET',
                    wrapAsMultipart: false

            switch (response.status) {
                case "200":
                    dsl.echo("Status : 200 OK")
                    break

                case "404":
                    dsl.error("Status: 404 Not Found")
                    break

                default:
                    dsl.error("Unmanaged response status: " + response.status + "\n response content: " + response.content)
            }

            if (response.content?.trim()) {
                def jsonObj = this.dsl.readJSON text: response.content
                mergeable = jsonObj.mergeable
            }
        }

        return mergeable
    }

    String getPullRequestNumber(String repositoryUrl, String sourceBranch, String targetBranch, String credentialId) {
        String pullRequestNumber
        def prMap = this.getResponsePullRequest(repositoryUrl, sourceBranch, targetBranch, credentialId)

        pullRequestNumber = prMap.keySet()[0]

        return pullRequestNumber
    }
}
 