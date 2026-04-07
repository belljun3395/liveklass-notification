package com.notification;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.notification.notification.domain.Notification;
import com.notification.notification.event.NotificationEvent;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;
import org.springframework.modulith.events.ApplicationModuleListener;

class ArchitectureRulesTest {

    static JavaClasses classes;

    static final ApplicationModules modules = ApplicationModules.of(NotificationApplication.class);

    private static final Set<String> INFRASTRUCTURE_MODULES = Set.of("infra", "support", "config");

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().importPackages("com.notification");
    }

    // ══════════════════════════════════════════════════════════════════
    // 모듈 경계 규칙
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("모듈 경계 규칙")
    class ModularityRules {

        @Test
        @DisplayName("모듈 간 경계 위반(사이클, 내부 패키지 직접 접근) 없는지 검증")
        void verifyModularity() {
            modules.verify();
        }

        @Test
        @DisplayName("모듈 문서 생성 (target/spring-modulith-docs/)")
        void documentModules() {
            new Documenter(modules)
                    .writeModulesAsPlantUml()
                    .writeIndividualModulesAsPlantUml()
                    .writeDocumentation();
        }

        @Test
        @DisplayName("support 모듈은 비즈니스 모듈에 의존하지 않는다")
        void support_should_not_depend_on_business_modules() {
            var businessPackages =
                    modules.stream()
                            .filter(
                                    m ->
                                            !INFRASTRUCTURE_MODULES.contains(
                                                    m.getIdentifier().toString()))
                            .map(m -> m.getBasePackage().getName() + "..")
                            .toArray(String[]::new);

            if (businessPackages.length == 0) return;

            noClasses()
                    .that()
                    .resideInAPackage("com.notification.support..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(businessPackages)
                    .check(classes);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 이벤트 발행 규칙
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName(
            "이벤트 발행 규칙: UseCase, Relay, Schedule, Processor, Service가 NotificationEventPublisher를 사용할 수 있다")
    class EventPublishingRules {

        @Test
        @DisplayName("NotificationEventPublisher를 사용하는 클래스는 허용된 패키지에만 존재해야 한다")
        void only_allowed_packages_may_use_event_publisher() {
            ArchRule rule =
                    noClasses()
                            .that()
                            .resideOutsideOfPackages(
                                    "..application..",
                                    "..event.relay..",
                                    "..event.schedule..",
                                    "..event.processor..",
                                    "..event.publisher..",
                                    "..service..")
                            .should()
                            .dependOnClassesThat()
                            .haveFullyQualifiedName(
                                    "com.notification.notification.event.publisher.NotificationEventPublisher");

            rule.check(classes);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Spring 어댑터 규칙
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Spring 어댑터 규칙")
    class SpringAdapterRules {

        @Test
        @DisplayName("@ApplicationModuleListener 메서드는 handle이라는 이름을 가져야 한다")
        void application_module_listener_methods_should_be_named_handle() {
            ArchRule rule =
                    methods()
                            .that()
                            .areAnnotatedWith(ApplicationModuleListener.class)
                            .should()
                            .haveName("handle");

            rule.check(classes);
        }

        @Test
        @DisplayName("*SpringAdapter 클래스는 event.listener.spring 패키지에 위치해야 한다")
        void spring_adapter_classes_should_be_in_event_listener_spring_package() {
            ArchRule rule =
                    classes()
                            .that()
                            .haveSimpleNameEndingWith("SpringAdapter")
                            .should()
                            .resideInAPackage("..event.listener.spring..");

            rule.check(classes);
        }

        @Test
        @DisplayName(
                "event.listener.spring 클래스는 event.processor 패키지에만 비즈니스 로직을 위임해야 한다 (service, repository, domain 직접 접근 금지)")
        void spring_adapters_should_not_directly_access_service_or_repository() {
            ArchRule rule =
                    noClasses()
                            .that()
                            .resideInAPackage("..event.listener.spring..")
                            .should()
                            .dependOnClassesThat()
                            .resideInAnyPackage("..service..", "..repository..");

            rule.check(classes);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Processor 규칙
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Processor 규칙")
    class ProcessorRules {

        @Test
        @DisplayName("이벤트 *Processor 클래스는 event.processor 패키지에 위치해야 한다 (service 후처리 Processor 제외)")
        void processor_classes_should_be_in_event_processor_package() {
            ArchRule rule =
                    classes()
                            .that()
                            .haveSimpleNameEndingWith("Processor")
                            .and()
                            .areNotMemberClasses()
                            .and()
                            .resideOutsideOfPackage("..service..")
                            .should()
                            .resideInAPackage("..event.processor..");

            rule.check(classes);
        }

        @Test
        @DisplayName("Processor는 event.listener 패키지에 의존하지 않는다 (단방향 의존)")
        void processors_should_not_depend_on_listeners() {
            ArchRule rule =
                    noClasses()
                            .that()
                            .resideInAPackage("..event.processor..")
                            .should()
                            .dependOnClassesThat()
                            .resideInAPackage("..event.listener..");

            rule.check(classes);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // UseCase 규칙
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("UseCase 규칙")
    class UseCaseRules {

        @Test
        @DisplayName("*UseCase 클래스는 application 패키지에 위치해야 한다")
        void use_case_classes_should_be_in_application_package() {
            ArchRule rule =
                    classes()
                            .that()
                            .haveSimpleNameEndingWith("UseCase")
                            .should()
                            .resideInAPackage("..application..");

            rule.check(classes);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Relay 규칙
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Relay 규칙")
    class RelayRules {

        @Test
        @DisplayName("*Relay 클래스는 event.relay 패키지에 위치해야 한다")
        void relay_classes_should_be_in_event_relay_package() {
            ArchRule rule =
                    classes()
                            .that()
                            .haveSimpleNameEndingWith("Relay")
                            .and()
                            .areNotMemberClasses()
                            .should()
                            .resideInAPackage("..event.relay..");

            rule.check(classes);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 서비스 레이어 규칙
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("서비스 레이어 규칙")
    class ServiceLayerRules {

        @Test
        @DisplayName("service 레이어는 event.listener, event.processor에 의존하지 않는다")
        void service_should_not_depend_on_event_listener_or_processor() {
            ArchRule rule =
                    noClasses()
                            .that()
                            .resideInAPackage("..service..")
                            .should()
                            .dependOnClassesThat()
                            .resideInAnyPackage("..event.listener..", "..event.processor..");

            rule.check(classes);
        }

        @Test
        @DisplayName("service 레이어는 application 레이어에 의존하지 않는다")
        void service_should_not_depend_on_application() {
            ArchRule rule =
                    noClasses()
                            .that()
                            .resideInAPackage("..service..")
                            .should()
                            .dependOnClassesThat()
                            .resideInAPackage("..application..");

            rule.check(classes);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Web 레이어 규칙
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Web 레이어 규칙")
    class WebLayerRules {

        @Test
        @DisplayName("web 레이어 DTO는 domain 패키지에 직접 의존하지 않는다")
        void web_dto_should_not_depend_on_domain() {
            ArchRule rule =
                    noClasses()
                            .that()
                            .resideInAPackage("..web.dto..")
                            .should()
                            .dependOnClassesThat()
                            .resideInAPackage("..domain..");

            rule.check(classes);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 이벤트 계약 규칙
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("이벤트 계약 규칙: 새 이벤트 추가 시 NotificationEvent 구현 강제")
    class EventContractRules {

        @Test
        @DisplayName("event 패키지의 *Event 클래스는 NotificationEvent를 구현해야 한다")
        void event_records_must_implement_notification_event() {
            ArchRule rule =
                    classes()
                            .that()
                            .resideInAPackage("com.notification.notification.event")
                            .and()
                            .haveSimpleNameEndingWith("Event")
                            .and()
                            .areNotInterfaces()
                            .should()
                            .implement(NotificationEvent.class);

            rule.check(classes);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Aggregate Root 규칙
    // ══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Aggregate Root 규칙")
    class AggregateRules {

        @Test
        @DisplayName("Notification의 상태 변경 메서드(mark*)는 package-private이어야 한다")
        void notification_state_methods_should_be_package_private() {
            var notificationClass = classes.get(Notification.class.getName());

            var publicMutators =
                    notificationClass.getMethods().stream()
                            .filter(m -> m.getName().startsWith("mark"))
                            .filter(
                                    m ->
                                            m.getModifiers()
                                                    .contains(
                                                            com.tngtech.archunit.core.domain
                                                                    .JavaModifier.PUBLIC))
                            .map(JavaMember::getName)
                            .toList();

            assertThat(publicMutators)
                    .withFailMessage(
                            "Notification의 다음 상태 변경 메서드가 public입니다. "
                                    + "NotificationJob을 통해서만 호출되어야 합니다: %s",
                            publicMutators)
                    .isEmpty();
        }
    }
}
