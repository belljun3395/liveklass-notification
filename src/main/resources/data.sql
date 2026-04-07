-- ============================================================
-- Notification Service Test Data (local 프로파일 전용)
-- ============================================================
-- application-local.yml 의 spring.sql.init.mode: always 설정 필요.
-- ON CONFLICT DO NOTHING 으로 멱등성 보장 (서버 재시작 시 중복 삽입 없음).

INSERT INTO notification_templates
    (id, code, channel, locale, version, title_template, body_template, description, variables, deleted, created_at, updated_at)
VALUES
(
    1,
    'enrollment-complete',
    'EMAIL',
    'ko',
    1,
    '{{name}}님, {{lectureTitle}} 수강 신청이 완료되었습니다',
    E'안녕하세요, {{name}}님.\n\n{{lectureTitle}} 수강 신청이 완료되었습니다.\n\n- 강사: {{instructorName}}\n- 시작일: {{startDate}}\n\n열심히 공부하세요!\n\nLiveKlass 드림',
    '수강 신청 완료 이메일',
    '[{"name":"name","dataType":"STRING","required":true,"exampleValue":"홍길동","description":"수신자 이름"},{"name":"lectureTitle","dataType":"STRING","required":true,"exampleValue":"Spring Boot 마스터 클래스","description":"강좌 제목"},{"name":"instructorName","dataType":"STRING","required":true,"exampleValue":"김강사","description":"강사 이름"},{"name":"startDate","dataType":"DATE","required":true,"exampleValue":"2026-05-01","description":"강의 시작일"}]',
    false,
    NOW(),
    NOW()
),
(
    2,
    'lecture-reminder',
    'EMAIL',
    'ko',
    1,
    '[D-1] {{name}}님, {{lectureTitle}} 강의가 내일 시작됩니다',
    E'안녕하세요, {{name}}님.\n\n수강 중인 {{lectureTitle}} 강의가 내일 시작됩니다.\n\n- 강사: {{instructorName}}\n- 시작일: {{startDate}}\n\n잊지 말고 참여해 주세요!\n\nLiveKlass 드림',
    '강의 시작 하루 전 리마인더',
    '[{"name":"name","dataType":"STRING","required":true,"exampleValue":"홍길동","description":"수신자 이름"},{"name":"lectureTitle","dataType":"STRING","required":true,"exampleValue":"Spring Boot 마스터 클래스","description":"강좌 제목"},{"name":"instructorName","dataType":"STRING","required":true,"exampleValue":"김강사","description":"강사 이름"},{"name":"startDate","dataType":"DATE","required":true,"exampleValue":"2026-05-01","description":"강의 시작일"}]',
    false,
    NOW(),
    NOW()
),
(
    3,
    'payment-complete',
    'EMAIL',
    'ko',
    1,
    '{{name}}님, {{lectureTitle}} 결제가 완료되었습니다',
    E'안녕하세요, {{name}}님.\n\n{{lectureTitle}} 결제가 완료되었습니다.\n\n지금 바로 수강을 시작해 보세요!\n\nLiveKlass 드림',
    '결제 완료 이메일',
    '[{"name":"name","dataType":"STRING","required":true,"exampleValue":"홍길동","description":"수신자 이름"},{"name":"lectureTitle","dataType":"STRING","required":true,"exampleValue":"Spring Boot 마스터 클래스","description":"강좌 제목"}]',
    false,
    NOW(),
    NOW()
)
ON CONFLICT (code, channel, locale, version) DO NOTHING;
