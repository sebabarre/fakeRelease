@Library('pipelineUtilities@FEATURE/COS-2194') _


def GROUP="sebastien_barre"
def REPOS_COMPONENT=["houston-connector-emeraude"]
def ALL_REPOS=["cosmo-kafka-serialization","houston-common","houston-parent"]+REPOS_COMPONENT
def JOBS_CI=["houston-parent","houston-common","cosmo_kafka_serialization_CI","houston-connector-emeraude"]


pipeline {
	agent any
	tools {
		maven 'maven3' 
	}
	environment {
		KFK_NEXT_DEV_VERSION=""
		HOUSTON_RELEASE_VERSION=""
		HOUSTON_NEXT_DEV_VERSION=""
		HESPERIDES_WORKING_COPY_VERSION=""
		RELEASE_KAFKA_SER=false
	}
	stages {
		stage('Validation des versions') {
			steps {
				dir('pomParent') {
					script {
						git url: "git@gitlab.socrate.vsct.fr:${GROUP}/houston-parent.git", branch: "develop"
						def pom = readMavenPom file:'pom.xml'

						//Calcul de la prochaine version de développement
						HOUSTON_NEXT_DEV_VERSION=pomUtils.pumpUpMinorVersionAndResetPatch(version: pom.getVersion())

						//Calcul de la prochaine version releasé
						HOUSTON_RELEASE_VERSION=pomUtils.removeSnaphot(version: pom.getVersion())

						//Calcul de la prochaine version de cosmo-kafkaser
						KFK_NEXT_DEV_VERSION=pomUtils.getArtifactVersionFromDependencyManagement(pom: pom, artifactId: "cosmo-kafka-serialization")
						if ("${KFK_NEXT_DEV_VERSION}".contains("SNAPSHOT")) {
							echo "RELEASER KAFKA-SER, TU DOIS"
							KFK_NEXT_DEV_VERSION=pomUtils.removeSnaphot(version: KFK_NEXT_DEV_VERSION)
							KFK_NEXT_DEV_VERSION=pomUtils.pumpUpMinorVersionAndResetPatch(version: KFK_NEXT_DEV_VERSION)
							RELEASE_KAFKA_SER=true
						} else {
							KFK_NEXT_DEV_VERSION="La version de Kafka-Ser dans le dep. mngmt est déjà une release."
						}

						//La version working copy d'hesperides est la version en cours
						HESPERIDES_WORKING_COPY_VERSION=pom.getVersion()

						//Validation des paramètres
						params = input(
							id: "releaseParams",
							message: "Merci de vérifier les paramètres générés !",
							parameters: [
								string(name: "KFK_NEXT_DEV_VERSION", defaultValue: "${KFK_NEXT_DEV_VERSION}", description: "Numéro de la prochaine version de dev de la librairie cosmo-kafka-serialization\nCe champ doit respecter le pattern ((\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}-SNAPSHOT)|^\$)"),
								string(name: "HOUSTON_RELEASE_VERSION", defaultValue: "${HOUSTON_RELEASE_VERSION}",description: "Numéro de version des différents composants de Houston.\nCe numéro doit respecter le pattern \\d{1,2}\\.\\d{1,2}\\.\\d{1,2}"),
								string(name: "HOUSTON_NEXT_DEV_VERSION", defaultValue: "${HOUSTON_NEXT_DEV_VERSION}", description: "Numéro de la prochaine version de dev des différents composants de Houston\nCe numéro doit respecter le pattern \\d{1,2}\\.\\d{1,2}\\.\\d{1,2}-SNAPSHOT"),
								string(name: "HESPERIDES_WORKING_COPY_VERSION", defaultValue: "${HESPERIDES_WORKING_COPY_VERSION}", description: "Version de la WorkingCopy Hesperides d'où sera tiré la Release\nCe numéro doit respecter le pattern \\d{1,2}\\.\\d{1,2}\\.\\d{1,2}(-SNAPSHOT)?")
							]
						)
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
		/*
		stage("Merge develop -> Master") {
			steps {
				script{
					gitUtils.mergeAllProjects(group: GROUP, repositories: ALL_REPOS, branchFrom: "develop", branchTo: "master")
					gitUtils.pushAllModifications(group: GROUP, repositories: ALL_REPOS, branch: "master")
				}
			}
		}
		//*/

		stage("Réactivation de l'intégration continue") {
			steps {
				script {
					jenkinsUtils.enableJobs(jobs: JOBS_CI, pause: 1000)
				}
			}
		}
	}
}