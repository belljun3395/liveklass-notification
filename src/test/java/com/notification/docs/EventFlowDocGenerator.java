package com.notification.docs;

import com.notification.notification.event.NotificationEvent;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * 이벤트 발행/소비 흐름 문서를 생성하는 독립 실행형 제너레이터.
 *
 * <p>ArchUnit의 {@link JavaClasses}를 사용하여 컴파일된 바이트코드에서 이벤트 흐름을 추출한다.
 *
 * <ul>
 *   <li>{@link NotificationEvent} 구현체 → 이벤트 목록
 *   <li>{@link ApplicationModuleListener} 메서드의 파라미터 → 소비자
 *   <li>NotificationEventPublisher를 참조하면서 이벤트 타입도 참조하는 클래스 → 발행자
 * </ul>
 *
 * <p><b>출력 위치:</b> {@code build/generated-docs/event/}
 *
 * <p>Gradle {@code generateEventFlowDocs} 태스크로 실행된다.
 */
public class EventFlowDocGenerator {

    private static final Path OUTPUT_DIR = Path.of("build/generated-docs/event");

    private static final String PUBLISHER_FQCN =
            "com.notification.notification.event.publisher.NotificationEventPublisher";
    private static final String EVENT_MARKER_FQCN = NotificationEvent.class.getName();
    private static final String LISTENER_FQCN = ApplicationModuleListener.class.getName();
    private static final Set<String> LISTENER_ANNOTATIONS =
            Set.of(
                    LISTENER_FQCN,
                    "org.springframework.context.event.EventListener",
                    "org.springframework.transaction.event.TransactionalEventListener");

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);

        JavaClasses classes = new ClassFileImporter().importPackages("com.notification");

        // 1. 이벤트 타입 수집
        List<JavaClass> eventTypes = findEventTypes(classes);

        // 2. 소비자 수집: @ApplicationModuleListener 메서드 → (이벤트 타입, 소비 클래스)
        Map<String, List<ConsumerInfo>> consumers = findConsumers(classes);

        // 3. 발행자 수집: NotificationEventPublisher 참조 + 이벤트 타입 참조
        Map<String, List<String>> publishers = findPublishers(classes, eventTypes);

        // 4. 문서 생성
        writeFlowTable(eventTypes, publishers, consumers);
        writeFlowDiagram(eventTypes, publishers, consumers, classes);

        System.out.println("Event flow docs generated → " + OUTPUT_DIR.toAbsolutePath());
    }

    // ── 분석 ──────────────────────────────────────────────────────

    private static List<JavaClass> findEventTypes(JavaClasses classes) {
        return classes.stream()
                .filter(c -> !c.isInterface())
                .filter(c -> c.isAssignableTo(NotificationEvent.class))
                .sorted(Comparator.comparing(JavaClass::getSimpleName))
                .toList();
    }

    private static Map<String, List<ConsumerInfo>> findConsumers(JavaClasses classes) {
        Map<String, List<ConsumerInfo>> result = new LinkedHashMap<>();

        for (JavaClass clazz : classes) {
            for (JavaMethod method : clazz.getMethods()) {
                boolean isListener =
                        method.getAnnotations().stream()
                                .anyMatch(
                                        a ->
                                                LISTENER_ANNOTATIONS.contains(
                                                        a.getRawType().getName()));
                if (!isListener) continue;

                method.getRawParameterTypes().stream()
                        .filter(p -> p.isAssignableTo(NotificationEvent.class))
                        .forEach(
                                eventType -> {
                                    // SpringAdapter → Processor 위임 관계 추적
                                    // Adapter가 위임하는 실제 Processor를 소비자로 표시한다.
                                    String consumerName = resolveDelegate(clazz);
                                    JavaClass consumerClass =
                                            findClassBySimpleName(classes, consumerName);
                                    String role = extractPackageRole(consumerClass);

                                    result.computeIfAbsent(
                                                    eventType.getSimpleName(),
                                                    k -> new ArrayList<>())
                                            .add(new ConsumerInfo(consumerName, role));
                                });
            }
        }
        return result;
    }

    /**
     * SpringAdapter가 위임하는 Processor를 찾는다. Adapter가 *Processor 타입 필드를 가지면 해당 Processor의 이름을 반환한다.
     */
    private static String resolveDelegate(JavaClass adapterClass) {
        return adapterClass.getAllFields().stream()
                .filter(f -> f.getRawType().getSimpleName().endsWith("Processor"))
                .map(f -> f.getRawType().getSimpleName())
                .findFirst()
                .orElse(adapterClass.getSimpleName());
    }

    private static JavaClass findClassBySimpleName(JavaClasses classes, String simpleName) {
        return classes.stream()
                .filter(c -> c.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Class not found: " + simpleName));
    }

    /**
     * 발행자를 찾는다. 이벤트 생성자({@code new Event(...)})나 정적 팩토리 메서드({@code Event.register(...)}) 호출을 기준으로
     * 판단한다.
     *
     * <p>이벤트의 getter/accessor 호출(소비자가 파라미터를 읽는 경우)은 제외한다.
     */
    private static Map<String, List<String>> findPublishers(
            JavaClasses classes, List<JavaClass> eventTypes) {
        Map<String, List<String>> result = new LinkedHashMap<>();

        Set<String> eventFqcns =
                eventTypes.stream().map(JavaClass::getName).collect(Collectors.toSet());

        for (JavaClass clazz : classes) {
            boolean usesPublisher =
                    clazz.getDirectDependenciesFromSelf().stream()
                            .anyMatch(dep -> dep.getTargetClass().getName().equals(PUBLISHER_FQCN));
            if (!usesPublisher) continue;

            Set<String> constructedEvents = new LinkedHashSet<>();

            // new Event(...) 생성자 호출
            clazz.getConstructorCallsFromSelf().stream()
                    .filter(call -> eventFqcns.contains(call.getTargetOwner().getName()))
                    .forEach(call -> constructedEvents.add(call.getTargetOwner().getSimpleName()));

            // Event.staticFactory(...) 정적 팩토리 메서드 호출
            // record의 getter (jobId(), scheduledAt() 등)는 제외한다
            clazz.getMethodCallsFromSelf().stream()
                    .filter(call -> eventFqcns.contains(call.getTargetOwner().getName()))
                    .filter(
                            call -> {
                                String methodName = call.getTarget().getName();
                                // record accessor/getter 패턴 제외
                                return !isRecordAccessor(call.getTargetOwner(), methodName);
                            })
                    .forEach(call -> constructedEvents.add(call.getTargetOwner().getSimpleName()));

            for (String eventName : constructedEvents) {
                result.computeIfAbsent(eventName, k -> new ArrayList<>())
                        .add(clazz.getSimpleName());
            }
        }
        return result;
    }

    /** record의 컴포넌트 accessor인지 판별한다. record의 필드명과 동일한 이름의 파라미터 없는 메서드는 accessor로 간주한다. */
    private static boolean isRecordAccessor(JavaClass eventClass, String methodName) {
        // record의 canonical constructor 파라미터 = record 컴포넌트 = accessor 이름
        // 바이트코드에서는 record 컴포넌트가 필드로 나타남
        return eventClass.getAllFields().stream().anyMatch(f -> f.getName().equals(methodName));
    }

    // ── 문서 생성 ─────────────────────────────────────────────────

    private static void writeFlowTable(
            List<JavaClass> eventTypes,
            Map<String, List<String>> publishers,
            Map<String, List<ConsumerInfo>> consumers)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("// Auto-generated by EventFlowDocGenerator. Do not edit manually.\n");
        sb.append("= 이벤트 발행/소비 흐름\n\n");

        sb.append("== 이벤트 흐름 다이어그램\n\n");
        sb.append("[plantuml, event-flow, svg]\n");
        sb.append("----\n");
        sb.append("include::event-flow.puml[]\n");
        sb.append("----\n\n");

        sb.append("== 이벤트 흐름 표\n\n");
        sb.append("[cols=\"2,3,3\", options=\"header\"]\n");
        sb.append("|===\n");
        sb.append("| 이벤트 | 발행 주체 | 소비 주체\n\n");

        for (JavaClass eventType : eventTypes) {
            String name = eventType.getSimpleName();
            List<String> pubs = publishers.getOrDefault(name, List.of("(없음)"));
            List<ConsumerInfo> cons = consumers.getOrDefault(name, List.of());

            sb.append("| `").append(name).append("`\n");
            sb.append("| ").append(String.join(" +\n", pubs)).append("\n");
            if (cons.isEmpty()) {
                sb.append("| (없음)\n");
            } else {
                sb.append("| ")
                        .append(
                                cons.stream()
                                        .map(c -> c.className + " (" + c.role + ")")
                                        .collect(Collectors.joining(" +\n")))
                        .append("\n");
            }
            sb.append("\n");
        }

        sb.append("|===\n\n");

        // 이벤트 체인 분석
        sb.append("== 이벤트 체인\n\n");
        sb.append("Processor가 다른 이벤트를 발행하는 경우, 이벤트 체인이 형성됩니다.\n\n");

        List<String> chains = buildChains(eventTypes, publishers, consumers);
        if (chains.isEmpty()) {
            sb.append("현재 이벤트 체인은 없습니다.\n");
        } else {
            for (String chain : chains) {
                sb.append("* ").append(chain).append("\n");
            }
        }

        write("event-flow.adoc", sb.toString());
    }

    private static void writeFlowDiagram(
            List<JavaClass> eventTypes,
            Map<String, List<String>> publishers,
            Map<String, List<ConsumerInfo>> consumers,
            JavaClasses classes)
            throws IOException {
        // 모든 컴포넌트를 역할별로 분류
        Map<String, Set<String>> componentsByRole = new LinkedHashMap<>();
        componentsByRole.put("Schedule", new LinkedHashSet<>());
        componentsByRole.put("Relay", new LinkedHashSet<>());
        componentsByRole.put("UC", new LinkedHashSet<>());
        componentsByRole.put("Service", new LinkedHashSet<>());
        componentsByRole.put("Processor", new LinkedHashSet<>());

        for (JavaClass eventType : eventTypes) {
            String eventName = eventType.getSimpleName();
            for (String pub : publishers.getOrDefault(eventName, List.of())) {
                JavaClass pubClass = findClassBySimpleName(classes, pub);
                String role = extractPackageRole(pubClass);
                componentsByRole.computeIfAbsent(role, k -> new LinkedHashSet<>()).add(pub);
            }
            for (ConsumerInfo con : consumers.getOrDefault(eventName, List.of())) {
                componentsByRole
                        .computeIfAbsent(con.role, k -> new LinkedHashSet<>())
                        .add(con.className);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("title Event Publishing / Consuming Flow\n");
        sb.append("top to bottom direction\n\n");

        sb.append("skinparam component {\n");
        sb.append("  BackgroundColor #6096C4\n");
        sb.append("  BorderColor #1A5276\n");
        sb.append("  FontColor white\n");
        sb.append("  FontStyle Bold\n");
        sb.append("}\n");
        sb.append("skinparam queue {\n");
        sb.append("  BackgroundColor #F39C12\n");
        sb.append("  BorderColor #D68910\n");
        sb.append("  FontColor white\n");
        sb.append("}\n");
        sb.append("skinparam rectangle {\n");
        sb.append("  BackgroundColor #F8F9FA\n");
        sb.append("  BorderColor #AEB6BF\n");
        sb.append("  FontColor #2C3E50\n");
        sb.append("  FontStyle Bold\n");
        sb.append("  RoundCorner 10\n");
        sb.append("}\n");
        sb.append("skinparam backgroundColor white\n\n");

        // 역할별 레이어 순서
        List<String> layerOrder = List.of("Schedule", "Relay", "UC", "Service");

        // 발행자 레이어
        for (String role : layerOrder) {
            Set<String> comps = componentsByRole.getOrDefault(role, Set.of());
            if (comps.isEmpty()) continue;
            sb.append("rectangle \"").append(role).append("\" {\n");
            for (String comp : comps) {
                sb.append("  component \"")
                        .append(shortClassName(comp))
                        .append("\" as ")
                        .append(classAlias(comp))
                        .append("\n");
            }
            sb.append("}\n\n");
        }

        // 이벤트 레이어
        sb.append("rectangle \"Event\" {\n");
        for (JavaClass eventType : eventTypes) {
            String eventName = eventType.getSimpleName();
            sb.append("  queue \"")
                    .append(shortEventName(eventName))
                    .append("\" as ")
                    .append(eventAlias(eventName))
                    .append("\n");
        }
        sb.append("}\n\n");

        // Processor 레이어
        Set<String> processors = componentsByRole.getOrDefault("Processor", Set.of());
        if (!processors.isEmpty()) {
            sb.append("rectangle \"Processor\" {\n");
            for (String comp : processors) {
                sb.append("  component \"")
                        .append(shortClassName(comp))
                        .append("\" as ")
                        .append(classAlias(comp))
                        .append("\n");
            }
            sb.append("}\n\n");
        }

        // 발행 화살표
        for (JavaClass eventType : eventTypes) {
            String eventName = eventType.getSimpleName();
            for (String pub : publishers.getOrDefault(eventName, List.of())) {
                sb.append(classAlias(pub))
                        .append(" --> ")
                        .append(eventAlias(eventName))
                        .append(" : publish\n");
            }
        }
        sb.append("\n");

        // 소비 화살표
        for (JavaClass eventType : eventTypes) {
            String eventName = eventType.getSimpleName();
            for (ConsumerInfo con : consumers.getOrDefault(eventName, List.of())) {
                sb.append(eventAlias(eventName))
                        .append(" --> ")
                        .append(classAlias(con.className))
                        .append(" : consume\n");
            }
        }

        sb.append("\n@enduml\n");
        write("event-flow.puml", sb.toString());
    }

    // ── 이벤트 체인 분석 ──────────────────────────────────────────

    private static List<String> buildChains(
            List<JavaClass> eventTypes,
            Map<String, List<String>> publishers,
            Map<String, List<ConsumerInfo>> consumers) {
        List<String> chains = new ArrayList<>();

        // consumer의 className이 다른 이벤트의 publisher에도 등장하면 체인
        for (JavaClass eventType : eventTypes) {
            String eventName = eventType.getSimpleName();
            List<ConsumerInfo> cons = consumers.getOrDefault(eventName, List.of());

            for (ConsumerInfo consumer : cons) {
                // 이 consumer가 발행하는 이벤트 찾기
                for (JavaClass targetEvent : eventTypes) {
                    String targetName = targetEvent.getSimpleName();
                    List<String> pubs = publishers.getOrDefault(targetName, List.of());
                    if (pubs.contains(consumer.className)) {
                        chains.add(
                                String.format(
                                        "`%s` → *%s* → `%s`",
                                        eventName, consumer.className, targetName));
                    }
                }
            }
        }
        return chains;
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────

    private static String extractPackageRole(JavaClass clazz) {
        String pkg = clazz.getPackageName();
        if (pkg.contains(".application")) return "UC";
        if (pkg.contains(".event.processor")) return "Processor";
        if (pkg.contains(".event.schedule")) return "Schedule";
        if (pkg.contains(".event.relay")) return "Relay";
        if (pkg.contains(".event.listener.spring")) return "Adapter";
        if (pkg.contains(".service")) return "Service";
        return "Other";
    }

    private static String shortEventName(String name) {
        return name.replace("Notification", "N.");
    }

    private static String shortClassName(String name) {
        return name.replace("NotificationJob", "NJ")
                .replace("Notification", "N.")
                .replace("SpringAdapter", "Adapter");
    }

    private static String eventAlias(String eventName) {
        return "evt_" + eventName.replaceAll("[^a-zA-Z0-9]", "");
    }

    private static String classAlias(String className) {
        return "cls_" + className.replaceAll("[^a-zA-Z0-9]", "");
    }

    private static void write(String filename, String content) throws IOException {
        Path path = OUTPUT_DIR.resolve(filename);
        Files.writeString(path, content);
        System.out.println("  wrote " + path);
    }

    private record ConsumerInfo(String className, String role) {}
}
