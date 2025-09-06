package ai.dsa.analysis.service;

import ai.dsa.analysis.config.PerplexityProperties;
import ai.dsa.analysis.dto.UserCodingData;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class PerplexityService {

    private final WebClient webClient;
    private final PerplexityProperties properties;
    /**
     * Quick health check for Perplexity API
     */
    public boolean isApiHealthy() {
        try {
            // Quick test to verify API key and connectivity
            if (properties.getKey() == null ||
                    properties.getKey().equals("not-configured") ||
                    properties.getKey().trim().isEmpty()) {
                log.debug("API key not configured");
                return false;
            }

            String testResponse = callPerplexityAPI("Hello, respond with 'OK'")
                    .timeout(Duration.ofSeconds(5))
                    .block();

            boolean healthy = testResponse != null && !testResponse.trim().isEmpty();
            log.debug("Perplexity API health check: {}", healthy ? "PASS" : "FAIL");
            return healthy;

        } catch (Exception e) {
            log.debug("Perplexity API health check failed: {}", e.getMessage());
            return false;
        }
    }


    public PerplexityService(@Qualifier("perplexityWebClient") WebClient webClient,
                             PerplexityProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
        log.info("🤖 Perplexity service initialized with model: {}", properties.getModel());
    }

    public String analyzeUserCodingPatterns(UserCodingData userData) {
        log.info("🔍 Starting AI analysis for user: {}", userData.getUserId());
        log.debug("🔑 API key prefix: {}...",
                properties.getKey() != null ? properties.getKey().substring(0, 8) : "null");

        // Build prompt
        String prompt = buildDetailedAnalysisPrompt(userData);
        log.debug("📝 Prompt sent to AI:\n{}", prompt);

        // Call Perplexity API
        Mono<String> responseMono = callPerplexityAPI(prompt)
                .doOnNext(resp -> log.debug("📋 Raw AI response JSON: {}", resp))
                .timeout(Duration.ofMillis(properties.getTimeout()))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)));

        // Block to get the response
        String aiResponse = responseMono.block();
        if (aiResponse == null || aiResponse.isBlank()) {
            log.warn("⚠️ Empty AI response, returning fallback");
            return generateFallbackAnalysis(userData);
        }

        log.info("✅ AI analysis successful for user: {}", userData.getUserId());
        return aiResponse;
    }

    private Mono<String> callPerplexityAPI(String prompt) {
        Map<String, Object> requestBody = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "You are an expert coding mentor; give actionable coding insights."),
                        Map.of("role", "user", "content", prompt)
                ),
                "max_tokens", properties.getMaxTokens(),
                "temperature", properties.getTemperature()
        );

        return webClient.post()
                .uri(properties.getBaseUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getKey())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::extractResponseContent)
                .doOnError(err -> log.error("🚨 API call failed: {}", err.getMessage()));
    }

    private String extractResponseContent(JsonNode response) {
        try {
            return response.path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();
        } catch (Exception e) {
            log.error("❌ Failed to parse AI response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse AI response", e);
        }
    }

    private String buildDetailedAnalysisPrompt(UserCodingData userData) {
        return String.format("""
            Analyze this developer's coding patterns and provide comprehensive insights:
            
            📊 CODING ACTIVITY:
            - Problems attempted: %d
            - Code executions: %d \s
            - Successful submissions: %d
            - Analysis period: %d days
            - Languages used: %s
            - Most used language: %s
            - Problem categories: %s
            
            🎯 PROVIDE DETAILED ANALYSIS:
            1. **Problem-Solving Style**: Describe their overall style (methodical, brute-force-first, optimization-focused, etc.)
            2. **Initial Approach Quality (1-5)**: How effective is their first attempt? Compare with how they solve similar problems later. Identify gaps in planning.
            3. **Test Case Thinking**: Evaluate how well they anticipate edge cases, corner scenarios, and tricky inputs.
            4. **Edge Case Handling**: Were unusual inputs and boundary conditions handled systematically or missed?
            5. **Complexity Awareness**: Did they consider time/space tradeoffs? Highlight cases of over-engineering or under-optimizing.
            6. **Error Pattern Analysis**: Identify common recurring mistakes (off-by-one, incorrect conditions, data structure misuse, etc.) and suggest prevention strategies.
            7. **Debugging & Iteration Style**: Assess how they handle failed attempts—quick fixes, structured debugging, or random retries.
            8. **Code Quality Score (1-5)**: Efficiency, readability, and adherence to best practices.
            9. **Key Strengths**: List 3–4 areas they excel at.
            10. **Areas for Improvement**: List 3–4 targeted weaknesses.
            11. **Actionable Recommendations**: Concrete, step-by-step improvements (e.g., “create a checklist for edge cases before coding”).
            12. **Learning Path**: Suggested topics, patterns, or resources to improve their weak areas.
            13. **Consistency & Growth**: Highlight whether they are improving, stagnating, or regressing over the analysis period.
            

            Please provide specific, actionable insights based on the data patterns.
            Format your response with clear sections and be constructive in your feedback.
            """,
                userData.getTotalProblems(),
                userData.getTotalRuns(),
                userData.getTotalSubmits(),
                userData.getAnalysisPeriodDays(),
                userData.getLanguagesUsed(),
                userData.getMostUsedLanguage(),
                userData.getProblemCategories()
        );
    }

    private String generateFallbackAnalysis(UserCodingData userData) {
        log.info("🔄 Generating enhanced fallback analysis for user: {}", userData.getUserId());

        return String.format("""
            ## 🎯 **COMPREHENSIVE CODING ANALYSIS**
            
            ### **Problem-Solving Approach**
            Based on your %d code executions across %d problems, you demonstrate a %s approach to problem-solving. 
            Your run-to-submit ratio of %.1f indicates %s testing habits.
            
            ### **Activity & Consistency** 
            Over %d days, you've maintained %s engagement with %.1f executions per day on average.
            You've tackled %d unique problems, showing %s problem diversity.
            
            ### **Technical Breadth**
            You're working with %d programming language(s): %s.
            %s
            
            ### **Growth Recommendations**
            • **Immediate Focus**: %s
            • **Skill Building**: Practice algorithm complexity analysis
            • **Next Level**: Explore system design concepts
            • **Long-term**: Join competitive programming for rapid improvement
            
            ### **Strengths Identified**
            Your analysis reveals: consistent practice habits, %s problem-solving persistence, and %s technical approach.
            
            *This enhanced heuristic analysis provides comprehensive insights. For AI-powered deep analysis, ensure your Perplexity API is configured.*
            """,
                userData.getTotalRuns(),
                userData.getTotalProblems(),
                userData.getTotalRuns() > userData.getTotalSubmits() * 3 ? "thorough, iterative" : "confident, direct",
                userData.getTotalSubmits() > 0 ? (double) userData.getTotalRuns() / userData.getTotalSubmits() : 0,
                userData.getTotalRuns() > userData.getTotalSubmits() * 3 ? "excellent" : "efficient",
                userData.getAnalysisPeriodDays(),
                userData.getTotalRuns() > 20 ? "high" : userData.getTotalRuns() > 5 ? "moderate" : "light",
                (double) userData.getTotalRuns() / userData.getAnalysisPeriodDays(),
                userData.getTotalProblems(),
                userData.getTotalProblems() > 10 ? "excellent" : userData.getTotalProblems() > 5 ? "good" : "developing",
                userData.getLanguagesUsed().size(),
                String.join(", ", userData.getLanguagesUsed()),
                userData.getLanguagesUsed().size() > 1 ? "This shows good technical versatility." : "Consider exploring additional languages for broader perspectives.",
                userData.getProblemCategories().size() <= 2 ? "Expand into new problem categories (Trees, Graphs, DP)" : "Continue diversifying problem types",
                userData.getTotalRuns() > userData.getTotalSubmits() * 3 ? "methodical" : "decisive",
                userData.getLanguagesUsed().size() > 1 ? "versatile" : "focused"
        );
    }
}
