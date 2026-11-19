package iot.platform.backend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packagesOf = BackendApplication.class)
class ServiceBoundaryTest {
  @ArchTest
  static final ArchRule backendDoesNotImportDeviceServices =
      noClasses()
          .that()
          .resideInAPackage("iot.platform.backend..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("iot.platform.gateway..", "iot.platform.simulator..");
}
