val user = scala.util.Properties.envOrElse("SONATYPE_USER", "sonatype-user")
val pass = scala.util.Properties.envOrElse("SONATYPE_PASS", "password")

credentials += Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass)

pomExtra in Global := {
  <url>https://github.com/artsy/atomic-store</url>
   <licenses>
    <license>
      <name>MIT</name>
      <url>https://opensource.org/licenses/MIT</url>
    </license>
  </licenses>
  <scm>
    <url>git@github.com:artsy/atomic-store.git</url>
    <connection>scm:git:git@github.com:artsy/atomic-store.git</connection>
  </scm>
  <developers>
    <developer>
      <id>acjay</id>
      <name>Alan Johnson</name>
      <url>https://github.com/acjay</url>
    </developer>
    <developer>
      <id>bhoggard</id>
      <name>Barry Hoggard</name>
      <url>https://github.com/bhoggard</url>
    </developer>
  </developers>
}

