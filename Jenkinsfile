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
    // stage('Test') {
    //   steps {
    //     sh 'mvn test --fail-never'
    //     sh 'mvn surefire-report:report'
    //   }
    // }
    // stage('Generate JavaDoc') {
    //   steps {
    //     sh 'mvn javadoc:javadoc --fail-never'
    //    }
    // }
    stage('K8s') {
      steps {
        sh 'sudo kubectl set image deployments/hello-node container-name=image-id'
      }
    }
  }
  post {
    always {
      archiveArtifacts artifacts: '**/target/site/**', fingerprint: true
      archiveArtifacts artifacts: '**/target/**/*.jar', fingerprint: true
      archiveArtifacts artifacts: '**/target/**/*.war', fingerprint: true
      // junit 'target/surefire-reports/*.xml'
      // archiveArtifacts artifacts: '**/target/site/apidocs/**', fingerprint: true, allowEmptyArchive: true
    }
  }
}
