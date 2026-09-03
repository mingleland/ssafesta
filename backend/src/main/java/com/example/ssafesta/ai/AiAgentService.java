package com.example.ssafesta.ai;

import com.example.ssafesta.booth.BoothAccessGuard;
import com.example.ssafesta.booth.BoothNotFoundException;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.LayoutAgentReferences;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ConstraintViolations;
import com.example.ssafesta.common.ErrorCode;
import com.example.ssafesta.common.PresenceField;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A booth's AI docent — register, read back, edit, delete (spec 007 US1, FR-001~FR-003).
 *
 * <p>Same shape as the other booth-scoped editors: editor guard, valid lease for writes, validate,
 * write. Reading works on an expired booth because FR-015 preserves the data and preservation you
 * cannot observe is not preservation.
 *
 * <p>Scope stops at Business DB. Handing these settings to FastAPI is spec 008's access contract,
 * which today carries only {@code agentId}/{@code status}/{@code leaseEndsAt}.
 */
@Service
public class AiAgentService {

    private static final int MAX_NAME = 100;

    /**
     * C-12 whitelists, as {@code String} sets rather than enums on purpose.
     *
     * <p>An enum would fail during deserialisation, before any code of ours runs, and the client
     * would get a parse error instead of "which field, and what may it be" (T-24). C-12 also says
     * {@code tone} is a tuning target, and widening a set is a smaller change than widening an enum
     * that other layers switch on.
     */
    private static final Set<String> ROLES = Set.of("PROJECT_DOCENT", "GUIDE");
    private static final Set<String> TONES = Set.of("FRIENDLY", "PROFESSIONAL", "ENTHUSIASTIC");
    private static final Set<String> LENGTHS = Set.of("SHORT", "MEDIUM", "LONG");

    private static final String DEFAULT_TONE = "FRIENDLY";
    private static final String DEFAULT_LENGTH = "MEDIUM";

    /** Only these two mean "the agent is still in use". Anything else is not ours to translate. */
    private static final String DOCUMENTS_FK = "ai_documents_agent_id_fkey";
    private static final String CONSULTATIONS_FK = "consultations_agent_id_fkey";

    private final AiAgentRepository agents;
    private final BoothAccessGuard accessGuard;
    private final LayoutAgentReferences layoutReferences;
    private final BoothRepository booths;
    private final AiAgentProperties properties;

    public AiAgentService(AiAgentRepository agents, BoothAccessGuard accessGuard,
                          LayoutAgentReferences layoutReferences,
                          BoothRepository booths, AiAgentProperties properties) {
        this.agents = agents;
        this.accessGuard = accessGuard;
        this.layoutReferences = layoutReferences;
        this.booths = booths;
        this.properties = properties;
    }

    // ── 등록 ────────────────────────────────────────────────────────────────

    @Transactional
    public AgentView create(Long boothId, Long userId, AgentCommand command) {
        accessGuard.requireActiveEditor(boothId, userId);

        String name = validatedName(command, true);
        String role = validatedChoice(command == null ? null : command.role, "role", ROLES, null, true);
        String prompt = validatedPrompt(command, true);
        String tone = validatedChoice(command.tone, "tone", TONES, DEFAULT_TONE, false);
        String length = validatedChoice(command.responseLength, "responseLength", LENGTHS,
                DEFAULT_LENGTH, false);
        int price = validatedPrice(command.servicePrice, 0);
        boolean handoff = validatedHandoff(command.handoffEnabled, Boolean.FALSE);
        List<String> topics = validatedTopics(command.forbiddenTopics);

        if (agents.findByBoothId(boothId).isPresent()) {
            throw new AiAgentLimitException(properties.perBoothLimit());
        }

        Instant now = Instant.now();
        AiAgent agent = new AiAgent(boothId, name, role, tone, prompt, length, price,
                handoff, topics, now);
        try {
            // saveAndFlush so the unique-index violation surfaces inside this catch. The pre-check
            // above answers the common case politely; this answers the race the same way (R-02).
            agent = agents.saveAndFlush(agent);
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.isViolationOf(exception, "ux_ai_agents_booth")) {
                throw new AiAgentLimitException(properties.perBoothLimit());
            }
            throw exception;
        }
        return AgentView.of(agent);
    }

    // ── 수정 ────────────────────────────────────────────────────────────────

    @Transactional
    public AgentView update(Long agentId, Long userId, AgentCommand command) {
        AiAgent agent = agents.findById(agentId)
                .orElseThrow(() -> new AiAgentNotFoundException(agentId));
        accessGuard.requireActiveEditor(agent.getBoothId(), userId);

        if (command == null || !command.hasAnyKey()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "수정할 내용이 없습니다.");
        }

        // Validate everything the client sent before touching the entity — a half-applied patch
        // would leave the agent in a state the client never asked for.
        String name = command.name.isPresent() ? validatedName(command, false) : null;
        String role = command.role.isPresent()
                ? validatedChoice(command.role, "role", ROLES, null, true) : null;
        String prompt = command.systemPrompt.isPresent() ? validatedPrompt(command, false) : null;
        String tone = command.tone.isPresent()
                ? validatedChoice(command.tone, "tone", TONES, null, true) : null;
        String length = command.responseLength.isPresent()
                ? validatedChoice(command.responseLength, "responseLength", LENGTHS, null, true) : null;
        Integer price = command.servicePrice.isPresent()
                ? validatedPrice(command.servicePrice, null) : null;
        Boolean handoff = command.handoffEnabled.isPresent()
                ? validatedHandoff(command.handoffEnabled, null) : null;
        List<String> topics = command.forbiddenTopics.isPresent()
                ? validatedTopics(command.forbiddenTopics) : null;

        boolean changed = apply(name, agent.getName(), agent::changeName)
                | apply(role, agent.getRole(), agent::changeRole)
                | apply(prompt, agent.getSystemPrompt(), agent::changeSystemPrompt)
                | apply(tone, agent.getTone(), agent::changeTone)
                | apply(length, agent.getResponseLength(), agent::changeResponseLength)
                | applyPrice(command, price, agent)
                | applyHandoff(handoff, agent)
                | applyTopics(command, topics, agent);
        if (changed) {
            agent.touch(Instant.now());
        }
        return AgentView.of(agent);
    }

    // ── 삭제 (C-14) ─────────────────────────────────────────────────────────

    /**
     * Refuses while anything still points at the agent, then deletes for real.
     *
     * <p>Three checks and one catch. The checks give a sentence naming what blocks it; the catch is
     * for the rows that appear between the check and the delete. Layout references have no foreign
     * key to fall back on, which is why the booth row is locked first — publish takes the same lock.
     */
    @Transactional
    public void delete(Long agentId, Long userId) {
        AiAgent agent = agents.findById(agentId)
                .orElseThrow(() -> new AiAgentNotFoundException(agentId));
        accessGuard.requireActiveEditor(agent.getBoothId(), userId);

        // The same lock publish takes, and taken before the reference check rather than after: the
        // check reads the layout, and a publish that commits between the read and the delete would
        // leave a public booth pointing at an agent that no longer exists (spec 007 C-14,
        // data-model A-3). A lock only one of the two sides takes is not a lock.
        booths.findWithLockById(agent.getBoothId())
                .orElseThrow(() -> new BoothNotFoundException(agent.getBoothId()));

        requireUnreferenced(agent);
        try {
            agents.delete(agent);
            agents.flush();
        } catch (DataIntegrityViolationException exception) {
            throw translateDeleteViolation(exception);
        }
    }

    /** Package-private so the translation can be tested without racing a real transaction. */
    static RuntimeException translateDeleteViolation(DataIntegrityViolationException exception) {
        if (ConstraintViolations.isViolationOf(exception, DOCUMENTS_FK)) {
            return AiAgentDeleteConflictException.byDocuments();
        }
        if (ConstraintViolations.isViolationOf(exception, CONSULTATIONS_FK)) {
            return AiAgentDeleteConflictException.byConsultations();
        }
        // Not a reference we know about — say nothing rather than something wrong.
        return exception;
    }

    private void requireUnreferenced(AiAgent agent) {
        if (layoutReferences.referencedByLayouts(agent.getBoothId(), agent.getId())) {
            throw AiAgentDeleteConflictException.byLayout();
        }
        if (agents.hasDocuments(agent.getId())) {
            throw AiAgentDeleteConflictException.byDocuments();
        }
        if (agents.hasConsultations(agent.getId())) {
            throw AiAgentDeleteConflictException.byConsultations();
        }
    }

    // ── 조회 ────────────────────────────────────────────────────────────────

    /**
     * No lease check: FR-015 keeps the data when a lease expires, and an owner who cannot read it
     * back has no way to see that it was kept. Editing stays blocked.
     *
     * <p>A list of zero or one. One agent per booth (C-13), but the shape stays a list so the
     * contract does not change if the limit ever rises.
     */
    @Transactional(readOnly = true)
    public List<AgentView> findByBooth(Long boothId, Long userId) {
        accessGuard.requireEditor(boothId, userId);
        return agents.findByBoothId(boothId).map(AgentView::of).map(List::of).orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public AgentView findOne(Long agentId, Long userId) {
        AiAgent agent = agents.findById(agentId)
                .orElseThrow(() -> new AiAgentNotFoundException(agentId));
        accessGuard.requireEditor(agent.getBoothId(), userId);
        return AgentView.of(agent);
    }

    // ── 검증 ────────────────────────────────────────────────────────────────

    private String validatedName(AgentCommand command, boolean required) {
        if (command == null || !command.name.isPresent()) {
            if (required) {
                throw reject("name", "AI 직원 이름을 입력해 주세요.");
            }
            throw new IllegalStateException("호출 전에 present 를 확인해야 한다");
        }
        String name = command.name.value();
        if (name == null || name.isBlank()) {
            throw reject("name", "AI 직원 이름을 입력해 주세요.");
        }
        if (name.length() > MAX_NAME) {
            throw reject("name", "AI 직원 이름이 너무 깁니다. (최대 " + MAX_NAME + "자)");
        }
        return name;
    }

    private String validatedPrompt(AgentCommand command, boolean required) {
        if (command == null || !command.systemPrompt.isPresent()) {
            if (required) {
                throw reject("systemPrompt", "AI 직원 지시문을 입력해 주세요.");
            }
            throw new IllegalStateException("호출 전에 present 를 확인해야 한다");
        }
        String prompt = command.systemPrompt.value();
        if (prompt == null || prompt.isBlank()) {
            throw reject("systemPrompt", "AI 직원 지시문을 입력해 주세요.");
        }
        return prompt; // 원문 그대로 — 사용자가 쓴 프롬프트다
    }

    /**
     * @param fallback what an absent key means on create; {@code null} with {@code required} makes
     *                 the key mandatory
     */
    private String validatedChoice(PresenceField<String> field, String jsonField,
                                   Set<String> allowed, String fallback, boolean required) {
        if (field == null || !field.isPresent()) {
            if (required) {
                throw reject(jsonField, jsonField + " 값을 입력해 주세요. " + allowedList(allowed));
            }
            return fallback;
        }
        String value = field.value();
        if (value == null || !allowed.contains(value)) {
            throw reject(jsonField, jsonField + " 값이 올바르지 않습니다. " + allowedList(allowed));
        }
        return value;
    }

    private static String allowedList(Set<String> allowed) {
        return "허용값: " + String.join(", ", allowed.stream().sorted().toList());
    }

    private Integer validatedPrice(PresenceField<Integer> field, Integer fallback) {
        if (field == null || !field.isPresent()) {
            return fallback;
        }
        Integer price = field.value();
        if (price == null || price < 0) {
            throw reject("servicePrice", "이용 가격은 0 이상이어야 합니다.");
        }
        return price;
    }

    /**
     * Rejects an explicit {@code null} rather than reading it as {@code false}.
     *
     * <p>{@code handoff_enabled} is {@code NOT NULL}, so {@code null} is not a value the client can
     * mean — and reading it as {@code false} would <b>turn handoff off</b> on a PATCH that only
     * meant to leave it alone, silently. Every other field goes through a {@code validated*} check;
     * this one had none, which is how it became the only field where null was a quiet write.
     */
    private Boolean validatedHandoff(PresenceField<Boolean> field, Boolean fallback) {
        if (field == null || !field.isPresent()) {
            return fallback;
        }
        Boolean handoff = field.value();
        if (handoff == null) {
            throw reject("handoffEnabled", "상담원 연결 여부는 true 또는 false 여야 합니다.");
        }
        return handoff;
    }

    /**
     * Items must not be blank. <b>No vocabulary whitelist and no count cap</b> — neither is defined
     * anywhere, and inventing numbers here would be deciding an open question (헌법 30조). The cap
     * is registered in {@code docs/26}.
     */
    private List<String> validatedTopics(PresenceField<List<String>> field) {
        List<String> topics = field.value();
        if (topics == null) {
            return null;
        }
        if (topics.stream().anyMatch(topic -> topic == null || topic.isBlank())) {
            throw reject("forbiddenTopics", "금지 주제에 빈 값을 넣을 수 없습니다.");
        }
        return topics;
    }

    private static boolean apply(String next, String current, Consumer<String> setter) {
        if (next == null || Objects.equals(next, current)) {
            return false;
        }
        setter.accept(next);
        return true;
    }

    private static boolean applyPrice(AgentCommand command, Integer price, AiAgent agent) {
        if (!command.servicePrice.isPresent() || price == agent.getServicePrice()) {
            return false;
        }
        agent.changeServicePrice(price);
        return true;
    }

    /** {@code null} means the key was absent — validation has already refused an explicit null. */
    private static boolean applyHandoff(Boolean handoff, AiAgent agent) {
        if (handoff == null || handoff == agent.isHandoffEnabled()) {
            return false;
        }
        agent.changeHandoffEnabled(handoff);
        return true;
    }

    private static boolean applyTopics(AgentCommand command, List<String> topics, AiAgent agent) {
        if (!command.forbiddenTopics.isPresent()) {
            return false;
        }
        // Compare through the entity's own view: null and [] are one value there, so clearing an
        // already-empty list is not a change and must not move updatedAt.
        List<String> next = topics == null ? List.of() : topics;
        if (next.equals(agent.getForbiddenTopics())) {
            return false;
        }
        agent.changeForbiddenTopics(topics);
        return true;
    }

    private ApiException reject(String jsonField, String message) {
        return ApiException.fieldInvalid(jsonField, message);
    }

    // ── 요청·응답 ───────────────────────────────────────────────────────────

    /** Not a {@code record} — see {@link PresenceField}. */
    public static final class AgentCommand {

        private final PresenceField<String> name = new PresenceField<>();
        private final PresenceField<String> role = new PresenceField<>();
        private final PresenceField<String> tone = new PresenceField<>();
        private final PresenceField<String> systemPrompt = new PresenceField<>();
        private final PresenceField<String> responseLength = new PresenceField<>();
        private final PresenceField<Integer> servicePrice = new PresenceField<>();
        private final PresenceField<Boolean> handoffEnabled = new PresenceField<>();
        private final PresenceField<List<String>> forbiddenTopics = new PresenceField<>();

        @JsonProperty("name")
        void setName(String value) { name.set(value); }

        @JsonProperty("role")
        void setRole(String value) { role.set(value); }

        @JsonProperty("tone")
        void setTone(String value) { tone.set(value); }

        @JsonProperty("systemPrompt")
        void setSystemPrompt(String value) { systemPrompt.set(value); }

        @JsonProperty("responseLength")
        void setResponseLength(String value) { responseLength.set(value); }

        @JsonProperty("servicePrice")
        void setServicePrice(Integer value) { servicePrice.set(value); }

        @JsonProperty("handoffEnabled")
        void setHandoffEnabled(Boolean value) { handoffEnabled.set(value); }

        @JsonProperty("forbiddenTopics")
        void setForbiddenTopics(List<String> value) { forbiddenTopics.set(value); }

        boolean hasAnyKey() {
            return name.isPresent() || role.isPresent() || tone.isPresent()
                    || systemPrompt.isPresent() || responseLength.isPresent()
                    || servicePrice.isPresent() || handoffEnabled.isPresent()
                    || forbiddenTopics.isPresent();
        }
    }

    /**
     * A {@code record}: every key is always present. {@code boothId} rides along because
     * {@code /agents/{id}} carries no booth in its path and the client needs the way back.
     */
    public record AgentView(Long agentId, Long boothId, String name, String role, String tone,
                            String systemPrompt, String responseLength, int servicePrice,
                            boolean handoffEnabled, List<String> forbiddenTopics) {

        static AgentView of(AiAgent agent) {
            return new AgentView(agent.getId(), agent.getBoothId(), agent.getName(), agent.getRole(),
                    agent.getTone(), agent.getSystemPrompt(), agent.getResponseLength(),
                    agent.getServicePrice(), agent.isHandoffEnabled(), agent.getForbiddenTopics());
        }
    }

    /** {@code { "agents": [...] }} — 0개 또는 1개 (C-13). */
    public record AgentListView(List<AgentView> agents) {

        public AgentListView {
            agents = List.copyOf(Objects.requireNonNull(agents));
        }
    }
}
