## AnnotationsMaker

### Usage

Adds vertical annotations to dashboards, using input label, placing it at "current timestamp" on X axis.

Only AWS dashboards are currently supported.

For AWS:

~~~~
addReleaseAnnotationToBoard 
~~~~

method must be called inside 

~~~~
withEnv(["AWS_PROFILE=${AWS_TARGET_PROFILE}"]) {}        
~~~~

block in order to provide valid aws credentials

### Example

~~~~
import it.sky.dp.jenkins.annotations.*

def annotationsMaker

stage('Add deploy annotations to dashboard'){
            steps {
                script{
                    def boardsList;
                    def envBoardList;
                    withEnv(runEnv) {
                        //use a profile with cloudwatch list-dashboards, get-dashboard, put-dashboard privileges
                        withEnv(["AWS_PROFILE=${AWS_TARGET_PROFILE}"]) {          
                            boardsList = sh(returnStdout: true, script: 'aws cloudwatch list-dashboards')
                                
                            envBoardList = new JsonSlurperClassic().parseText( boardsList ).DashboardEntries
                            
                            //i filtered by ENV in dashboard name becasue list-dashboards returns all dashboards in account
                            envBoardList.retainAll { it.DashboardName.endsWith(env.TARGET_ENV) }
                                
                            //this would be the string applied, so shouldn't be too verbose
                            String annotationLabel = "db-migration-" + env.BUILD_NUMBER
                            
                            annotationsMaker = new AwsAnnotationsMaker(this,env)

                            //i annotated every board (according with previous filter)
                            envBoardList.each{ board ->
                                try{
                                    def putDashboardResult = annotationsMaker.addReleaseAnnotationToBoard(board.DashboardName, annotationLabel)
                                    println putDashboardResult
                                }catch(Exception ex){
                                    println ex
                                    slackMessage = "can't annotate dashboards!"
                                    warningState = true
                                }        
                            }
                        }
                    }     
                }
            }
        }
~~~~

