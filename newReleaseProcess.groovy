import static groovy.json.JsonOutput.*

@Library('hesperides@1.0.3')
@Library('pipelineUtilities@FIX/RELEASEV2') _

def REPOS_COMPONENT=["houston-connector-pmt","houston-connector-pao"]
def ALL_REPOS=["cosmo-kafka-serialization","houston-common","houston-parent"]+REPOS_COMPONENT
def JOBS_CI=["houston-parent","houston-common","cosmo_kafka_serialization_CI","houston-connector-emeraude"]
ROLLBACK_PROJECTS=[]

def doRollback() {
	echo "ENTER ROLLBACK"
	if (!params.IS_DRY_RUN) {
		if (RELEASE_KAFKA_SER == true) {
			pomUtils.setArtifactVersionInDependencyManagement(group: GROUP, repository:"houston-parent", artifactId:"cosmo-kafka-serialization", version: KFK_CURRENT_VERSION, isDryRun: params.IS_DRY_RUN, forcePull: true)
			jenkinsUtils.rollbackThisProject(group: GROUP, repository:"cosmo-kafka-serialization", lastVersion: KFK_CURRENT_VERSION)
		}
		pomUtils.setArtifactVersionInDependencyManagement(group: GROUP, repository:"houston-parent", artifactId:"houston-common", version: HOUSTON_CURRENT_VERSION, isDryRun: params.IS_DRY_RUN, forcePull: true)
		jenkinsUtils.rollbackThoseProjects(group: GROUP, repositories:ROLLBACK_PROJECTS, lastVersion: HOUSTON_CURRENT_VERSION, HasParentToUpdate: true)
	}
}

pipeline {
	agent any
	tools {
		maven 'maven3' 
	}
	environment {
		GROUP="sebastien_barre"
		KFK_NEXT_DEV_VERSION=""
		HOUSTON_RELEASE_VERSION=""
		HOUSTON_NEXT_DEV_VERSION=""
		HESPERIDES_WORKING_COPY_VERSION=""
		RELEASE_KAFKA_SER=false
		KFK_RELEASE_VERSION=""
		HOUSTON_CURRENT_VERSION=""
	}
	stages {
		stage('Validation des versions') {
			steps {
				dir('pomParent') {
					deleteDir()
					script {
						git url: "git@gitlab.socrate.vsct.fr:${GROUP}/houston-parent.git", branch: "develop"
						def pom = readMavenPom file:'pom.xml'
						HOUSTON_CURRENT_VERSION=pom.getVersion()
						//Calcul de la prochaine version de développement
						HOUSTON_NEXT_DEV_VERSION=pomUtils.pumpUpMinorVersionAndResetPatch(version: HOUSTON_CURRENT_VERSION)

						//Calcul de la prochaine version releasé
						HOUSTON_RELEASE_VERSION=pomUtils.removeSnaphot(version: HOUSTON_CURRENT_VERSION)

						//Calcul de la prochaine version de cosmo-kafkaser
						KFK_CURRENT_VERSION=pomUtils.getArtifactVersionFromDependencyManagement(pom: pom, artifactId: "cosmo-kafka-serialization")
						if ("${KFK_CURRENT_VERSION}".contains("SNAPSHOT")) {
							echo "RELEASER KAFKA-SER, TU DOIS"
							KFK_NEXT_DEV_VERSION=pomUtils.pumpUpMinorVersionAndResetPatch(version: KFK_CURRENT_VERSION)
							RELEASE_KAFKA_SER=true
						} else {
							KFK_NEXT_DEV_VERSION="La version de Kafka-Ser dans le dep. mngmt est déjà une release."
							ALL_REPOS.remove("cosmo-kafka-serialization")
						}

						//La version working copy d'hesperides est la version en cours
						HESPERIDES_WORKING_COPY_VERSION=HOUSTON_CURRENT_VERSION

						//Validation des paramètres
						params = input(
							id: "releaseParams",
							message: "Merci de vérifier les paramètres générés !",
							parameters: [
								string(name: "KFK_NEXT_DEV_VERSION", defaultValue: "${KFK_NEXT_DEV_VERSION}", description: "Numéro de la prochaine version de dev de la librairie cosmo-kafka-serialization"),
								string(name: "HOUSTON_RELEASE_VERSION", defaultValue: "${HOUSTON_RELEASE_VERSION}",description: "Numéro de version des différents composants de Houston."),
								string(name: "HOUSTON_NEXT_DEV_VERSION", defaultValue: "${HOUSTON_NEXT_DEV_VERSION}", description: "Numéro de la prochaine version de dev des différents composants de Houston"),
								string(name: "HESPERIDES_WORKING_COPY_VERSION", defaultValue: "${HESPERIDES_WORKING_COPY_VERSION}", description: "Version de la WorkingCopy Hesperides d'où sera tiré la Release"),
								booleanParam(name: "IS_DRY_RUN", defaultValue: true, description: "Mode dry run, c'est à dire que tout est fait en local, pour test")
							]
						)
						jenkinsUtils.printMap(params)
					}
				}
			}
		}

		stage("Désactivation de l'intégration continue") {
			steps {
				script {
					jenkinsUtils.disableJobs(jobs: JOBS_CI)
				}
			}
		}
		
		stage("Merge develop -> Master") {
			steps {
				script{
					gitUtils.mergeAllProjects(group: GROUP, repositories: ALL_REPOS, branchFrom: "develop", branchTo: "master")
					if (!params.IS_DRY_RUN) {
						gitUtils.pushAllModifications(group: GROUP, repositories: ALL_REPOS, branch: "master")
					}
				}
			}
		}

		stage("Release cosmo-kafka-serialization") {
			when {
				expression { RELEASE_KAFKA_SER == true }
			}
			steps {
				script {
					KFK_RELEASE_VERSION = releaseUtils.releaseThisProject(group: GROUP, repository:"cosmo-kafka-serialization", nextVersion: params.KFK_NEXT_DEV_VERSION, isDryRun: params.IS_DRY_RUN)
				}
			}
			post {
				failure {
					script {
						if (!params.IS_DRY_RUN) {
							jenkinsUtils.rollbackThisProject(group: GROUP, repository:"cosmo-kafka-serialization", lastVersion: KFK_CURRENT_VERSION)
						}
					}
				}
			}
		}


		stage("Mise à jour du dependency management - release") {
			steps {
				script {
					if (RELEASE_KAFKA_SER == true) {
						pomUtils.setArtifactVersionInDependencyManagement(group: GROUP, repository:"houston-parent", artifactId:"cosmo-kafka-serialization", version: KFK_RELEASE_VERSION, isDryRun: params.IS_DRY_RUN)
					}
					pomUtils.setArtifactVersionInDependencyManagement(group: GROUP, repository:"houston-parent", artifactId:"houston-common", version: params.HOUSTON_RELEASE_VERSION, isDryRun: params.IS_DRY_RUN)
				}
			}
			post {
				failure {
					script {
						doRollback()
					}
				}
			}
		}

		stage("Release houston-parent") {
			steps {
				script {
					ROLLBACK_PROJECTS.push("houston-parent")
					releaseUtils.releaseThisProject(group: GROUP, repository:"houston-parent", nextVersion: params.HOUSTON_NEXT_DEV_VERSION, isDryRun: params.IS_DRY_RUN)
				}
			}
			post {
				failure {
					script {
						doRollback()
					}
				}
			}
		}

		stage("Release houston-common") {
			steps {
				script {
					ROLLBACK_PROJECTS.push("houston-common")
					releaseUtils.releaseThisProject(group: GROUP, repository:"houston-common", nextVersion: params.HOUSTON_NEXT_DEV_VERSION, isDryRun: params.IS_DRY_RUN)
				}
			}
			post {
				failure {
					script {
						doRollback()
					}
				}
			}
		}

		stage("Release component") {
			steps {
				script {
					ROLLBACK_PROJECTS+=REPOS_COMPONENT
					releaseUtils.releaseThoseProjectsInParallel(group: GROUP, repositories:REPOS_COMPONENT, nextVersion: params.HOUSTON_NEXT_DEV_VERSION, isDryRun: params.IS_DRY_RUN)
				}
			}
			post {
				failure {
					script {
						doRollback()
					}
				}
			}
		}

		stage("Mise à jour du dependency management - snapshot") {
			steps {
				script {
					pomUtils.setArtifactVersionInDependencyManagement(group: GROUP, repository:"houston-parent", artifactId:"houston-common", version: params.HOUSTON_NEXT_DEV_VERSION, isDryRun: params.IS_DRY_RUN)

					if (!params.IS_DRY_RUN) {
						gitUtils.pushAllModifications(group: GROUP, repositories: ["houston-parent"], branch: "master")
					}
				}
			}
		}


		stage("Merge Master -> Develop") {
			steps {
				script{
					gitUtils.mergeAllProjects(group: GROUP, repositories: ALL_REPOS, branchFrom: "master", branchTo: "develop", cleanWorkspace: !params.IS_DRY_RUN)
					if (!params.IS_DRY_RUN) {
						gitUtils.pushAllModifications(group: GROUP, repositories: ALL_REPOS, branch: "develop")
					}
				}
			}
		}

		stage("Hespérides") {
			environment {
				AUTH = credentials('rest_hesperides')
				API_ROOT_URL = 'https://hesperides-dev:56789/'
			}
			when {
				expression { params.IS_DRY_RUN == false }
			}
			steps {
				script {
					releaseUtils.releaseThoseHesperidesWorkingCopy(
						projects: REPOS_COMPONENT,
						apiRootUrl: API_ROOT_URL,
						auth: AUTH,
						workingcopyVersion: "${params.HESPERIDES_WORKING_COPY_VERSION}",
						releaseVersion: "${params.HOUSTON_RELEASE_VERSION}",
						nextSnapshot: "${params.HOUSTON_NEXT_DEV_VERSION}")
					
					releaseUtils.setPlatformHesperidesVersion(
						app: "CSM",
						apiRootUrl: API_ROOT_URL,
						auth: AUTH,
						platform: "REL1",
						version: "${params.HOUSTON_NEXT_DEV_VERSION}")

					releaseUtils.setPlatformHesperidesVersion(
						app: "CSM",
						apiRootUrl: API_ROOT_URL,
						auth: AUTH,
						platform: "REL2",
						version: "${params.HOUSTON_RELEASE_VERSION}")
				}
			}
		}

		stage("Release Notes") {
			when {
				expression { params.IS_DRY_RUN == false }
			}
			steps {
				 script {
				 	try {
					 	def previousVersion=pomUtils.removeSnaphot(version: params.HESPERIDES_WORKING_COPY_VERSION)
			            build job: 'Create_Release_Note', parameters: [string(name: 'FROM_TAG', value: previousVersion), string(name: 'TO_TAG', value: params.HOUSTON_RELEASE_VERSION)]
			        } catch (Exception e) {
			        	echo e.getMessage()
			        }
			    }
			}
		}

		stage("Sonar & Checkmarx") {
			when {
				expression { params.IS_DRY_RUN == false }
			}
			steps {
				withSonarQubeEnv('Sonar566') {
					script {
						try {
							releaseUtils.sonarAndCheckmarxThoseProjects(projects: ALL_REPOS, group: GROUP)
						} catch (Exception e) {
		        			echo e.getMessage()
							echo "Error lors de l'étape Sonar - checkmarx"
						}
					}
				}
			}
		}
		
		stage("Réactivation de l'intégration continue") {
			steps {
				script {
					jenkinsUtils.enableJobs(jobs: JOBS_CI, pause: 1000)
				}
			}
		}
	}
}