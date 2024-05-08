pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        sh 'mvn -B -DskipTests clean package'
      }
    }
    stage('pmd') {
      steps {
        sh 'mvn pmd:pmd'
      }
    }
    stage('Test') {
      steps {
        sh 'mvn test'
      }
    }
    stage('Generate JavaDoc') {
      steps {
        sh 'mvn javadoc:jar'
       }
    }
  }
  post {
    always {
      archiveArtifacts artifacts: '**/target/site/**', fingerprint: true
      archiveArtifacts artifacts: '**/target/**/*.jar', fingerprint: true
      archiveArtifacts artifacts: '**/target/**/*.war', fingerprint: true
      junit 'target/surefire-reports/*.xml'
      archiveArtifacts artifacts: '**/target/site/apidocs/**', fingerprint: true
    }
  }
}