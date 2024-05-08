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
    stage('Generate JavaDoc') {
      steps {
        // 使用Maven生成JavaDoc
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
