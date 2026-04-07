package com.notification.docs;

import com.notification.notification.domain.JobNotificationPolicy;
import com.notification.notification.domain.JobStatus;
import com.notification.notification.domain.NotificationStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Job / Notification 상태 전이 문서를 생성하는 독립 실행형 제너레이터.
 *
 * <p>{@link JobStatus}, {@link NotificationStatus}, {@link JobNotificationPolicy} enum을 단일 truth
 * source로 삼아 PlantUML 다이어그램과 AsciiDoc 표 스니펫을 생성한다.
 *
 * <p><b>출력 위치:</b> {@code build/generated-docs/state/}
 *
 * <ul>
 *   <li>{@code job-state-transition.puml}
 *   <li>{@code notification-state-transition.puml}
 *   <li>{@code job-state-transition.adoc}
 *   <li>{@code notification-state-transition.adoc}
 * </ul>
 *
 * <p>Gradle {@code generateStateDocs} 태스크로 실행된다 ({@code build.gradle} 참조).
 */
public class StateTransitionDocGenerator {

    private static final Path OUTPUT_DIR = Path.of("build/generated-docs/state");

    private static final Map<JobStatus, String[]> JOB_STATE_DESC =
            Map.of(
                    JobStatus.CREATED, new String[] {"초기", "Job이 생성된 초기 상태. 발송 시각 확정 전"},
                    JobStatus.SCHEDULED, new String[] {"진행", "발송 시각이 확정되어 스케줄러에 등록된 상태"},
                    JobStatus.PROCESSING, new String[] {"진행", "알림 발송이 실제로 진행 중인 상태"},
                    JobStatus.COMPLETED, new String[] {"종료 (terminal)", "모든 알림 발송이 완료된 최종 상태"},
                    JobStatus.FAILED, new String[] {"오류", "처리 중 실패한 상태. RETRYING으로 전이 가능"},
                    JobStatus.CANCELLED, new String[] {"취소", "Job이 취소된 상태. RETRYING으로 재시도 가능"},
                    JobStatus.RETRYING, new String[] {"진행", "실패·취소 후 재처리를 준비 중인 상태"});

    private static final Map<NotificationStatus, String[]> NOTIFICATION_STATE_DESC =
            Map.of(
                    NotificationStatus.PENDING,
                    new String[] {"초기", "알림이 생성되어 발송 대기 중인 상태"},
                    NotificationStatus.SENDING,
                    new String[] {"진행", "외부 채널로 발송 요청이 진행 중인 상태"},
                    NotificationStatus.SENT,
                    new String[] {"종료 (terminal)", "발송이 성공적으로 완료된 최종 상태"},
                    NotificationStatus.FAILED,
                    new String[] {"오류", "발송 실패. RETRY_WAITING 또는 DEAD_LETTER로 전이 가능"},
                    NotificationStatus.RETRY_WAITING,
                    new String[] {"대기", "재시도 인터벌을 기다리는 상태. 일정 시간 후 SENDING으로 전이"},
                    NotificationStatus.DEAD_LETTER,
                    new String[] {"격리", "재시도 한도 초과 또는 stuck 복구로 격리된 상태. 수동 개입 필요"},
                    NotificationStatus.CANCELLED,
                    new String[] {"취소", "알림이 취소된 상태. RETRY_WAITING으로 복구 가능"});

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT_DIR);

        writeJobStateDiagram();
        writeNotificationStateDiagram();
        writeJobStateTable();
        writeNotificationStateTable();

        System.out.println("State transition docs generated → " + OUTPUT_DIR.toAbsolutePath());
    }

    // ── PlantUML ───────────────────────────────────────────────────

    private static void writeJobStateDiagram() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("title Job State Transitions\n");
        sb.append("hide empty description\n\n");
        appendStateSkinparam(sb);

        sb.append("[*] --> CREATED\n\n");

        for (JobStatus from : JobStatus.values()) {
            Set<JobStatus> nexts = from.getAllowedTransitions();
            if (nexts.isEmpty()) {
                sb.append(from).append(" --> [*]\n");
            } else {
                for (JobStatus to : nexts) {
                    String label = policyLabel(from, to);
                    sb.append(from).append(" --> ").append(to);
                    if (!label.isEmpty()) {
                        sb.append(" : ").append(label);
                    }
                    sb.append("\n");
                }
            }
        }

        sb.append("\n@enduml\n");
        write("job-state-transition.puml", sb.toString());
    }

    private static void writeNotificationStateDiagram() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("@startuml\n");
        sb.append("title Notification State Transitions\n");
        sb.append("hide empty description\n\n");
        appendStateSkinparam(sb);

        sb.append("[*] --> PENDING\n\n");

        for (NotificationStatus from : NotificationStatus.values()) {
            Set<NotificationStatus> nexts = from.getAllowedTransitions();
            if (nexts.isEmpty()) {
                sb.append(from).append(" --> [*]\n");
            } else {
                for (NotificationStatus to : nexts) {
                    String label = notificationPolicyLabel(from, to);
                    sb.append(from).append(" --> ").append(to);
                    if (!label.isEmpty()) {
                        sb.append(" : ").append(label);
                    }
                    sb.append("\n");
                }
            }
        }

        sb.append("\n@enduml\n");
        write("notification-state-transition.puml", sb.toString());
    }

    private static void appendStateSkinparam(StringBuilder sb) {
        sb.append("skinparam state {\n");
        sb.append("  BackgroundColor #6096C4\n");
        sb.append("  BorderColor #1A5276\n");
        sb.append("  FontColor white\n");
        sb.append("  FontStyle Bold\n");
        sb.append("  ArrowColor #555555\n");
        sb.append("  ArrowFontColor #555555\n");
        sb.append("}\n");
        sb.append("skinparam backgroundColor white\n\n");
    }

    // ── AsciiDoc ───────────────────────────────────────────────────

    private static void writeJobStateTable() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("// Auto-generated by StateTransitionDocGenerator. Do not edit manually.\n");
        sb.append("= Job 상태 전이\n\n");

        sb.append("== 상태 전이 다이어그램\n\n");
        sb.append("[plantuml, job-state-transition, svg]\n");
        sb.append("----\n");
        sb.append("include::job-state-transition.puml[]\n");
        sb.append("----\n\n");

        sb.append("== 상태 설명\n\n");
        sb.append("[cols=\"2,1,4\", options=\"header\"]\n");
        sb.append("|===\n");
        sb.append("| 상태 | 유형 | 설명\n");
        for (JobStatus s : JobStatus.values()) {
            String[] desc = JOB_STATE_DESC.getOrDefault(s, new String[] {"-", "-"});
            sb.append("| ").append(s);
            sb.append(" | ").append(desc[0]);
            sb.append(" | ").append(desc[1]);
            sb.append("\n");
        }
        sb.append("|===\n\n");

        sb.append("== 상태 전이 목록\n\n");
        sb.append("[cols=\"1,1,2\", options=\"header\"]\n");
        sb.append("|===\n");
        sb.append("| From | To | 정책\n");
        for (JobStatus from : JobStatus.values()) {
            for (JobStatus to : sorted(from.getAllowedTransitions())) {
                String label = policyLabel(from, to);
                sb.append("| ").append(from);
                sb.append(" | ").append(to);
                sb.append(" | ").append(label.isEmpty() ? "-" : label);
                sb.append("\n");
            }
        }
        sb.append("|===\n");

        write("job-state-transition.adoc", sb.toString());
    }

    private static void writeNotificationStateTable() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("// Auto-generated by StateTransitionDocGenerator. Do not edit manually.\n");
        sb.append("= Notification 상태 전이\n\n");

        sb.append("== 상태 전이 다이어그램\n\n");
        sb.append("[plantuml, notification-state-transition, svg]\n");
        sb.append("----\n");
        sb.append("include::notification-state-transition.puml[]\n");
        sb.append("----\n\n");

        sb.append("== 상태 설명\n\n");
        sb.append("[cols=\"2,1,4\", options=\"header\"]\n");
        sb.append("|===\n");
        sb.append("| 상태 | 유형 | 설명\n");
        for (NotificationStatus s : NotificationStatus.values()) {
            String[] desc = NOTIFICATION_STATE_DESC.getOrDefault(s, new String[] {"-", "-"});
            sb.append("| ").append(s);
            sb.append(" | ").append(desc[0]);
            sb.append(" | ").append(desc[1]);
            sb.append("\n");
        }
        sb.append("|===\n\n");

        sb.append("== 상태 전이 목록\n\n");
        sb.append("[cols=\"1,1,2\", options=\"header\"]\n");
        sb.append("|===\n");
        sb.append("| From | To | 정책\n");
        for (NotificationStatus from : NotificationStatus.values()) {
            for (NotificationStatus to : sorted(from.getAllowedTransitions())) {
                String policy = notificationPolicyLabel(from, to);
                sb.append("| ").append(from);
                sb.append(" | ").append(to);
                sb.append(" | ").append(policy.isEmpty() ? "-" : policy);
                sb.append("\n");
            }
        }
        sb.append("|===\n");

        write("notification-state-transition.adoc", sb.toString());
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static String policyLabel(JobStatus from, JobStatus to) {
        for (JobNotificationPolicy p : JobNotificationPolicy.values()) {
            if (p.getFromStatuses().contains(from) && p.getToStatus() == to) {
                return p.name();
            }
        }
        return "";
    }

    private static String notificationPolicyLabel(NotificationStatus from, NotificationStatus to) {
        for (JobNotificationPolicy p : JobNotificationPolicy.values()) {
            var t = JobNotificationPolicy.NotificationTransition.of(from, to);
            if (p.getNotificationTransitions().contains(t)) {
                return p.name();
            }
        }
        return "";
    }

    private static <T extends Enum<T>> Iterable<T> sorted(Set<T> set) {
        T[] arr = set.toArray((T[]) new Enum[0]);
        Arrays.sort(arr, (a, b) -> a.name().compareTo(b.name()));
        return Arrays.asList(arr);
    }

    private static void write(String filename, String content) throws IOException {
        Path path = OUTPUT_DIR.resolve(filename);
        Files.writeString(path, content);
        System.out.println("  wrote " + path);
    }
}
