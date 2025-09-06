package ai.dsa.analysis.service;

import ai.dsa.analysis.dto.AnalysisResult;
import ai.dsa.analysis.dto.UserCodingData;
import ai.dsa.analysis.model.CodingAnalysisResult;
import ai.dsa.analysis.repository.CodingAnalysisRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for analyzing user coding patterns with AI-powered insights.
 * Integrates with Perplexity AI for advanced analysis and recommendations.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnalysisService {

    private final DataAggregationService dataAggregationService;
    private final CodingAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final PerplexityService perplexityService;

    /**
     * Analyzes user coding patterns within the specified period.
     * Uses AI analysis with fallback to heuristics if AI is unavailable.
     *
     * @param userId     The user ID to analyze
     * @param periodDays Number of days to look back for analysis
     * @return AnalysisResult containing AI-powered insights and recommendations
     */
    @Transactional
    public AnalysisResult analyzeUserCodingPatterns(String userId, int periodDays) {
        // Input validation
        if (userId == null || userId.trim().isEmpty()) {
            return AnalysisResult.builder()
                    .success(false)
                    .errorMessage("User ID cannot be null or empty")
                    .build();
        }

        if (periodDays <= 0 || periodDays > 365) {
            return AnalysisResult.builder()
                    .success(false)
                    .errorMessage("Period days must be between 1 and 365")
                    .build();
        }

        log.info("🤖 Starting AI analysis for user: {} (period: {} days)", userId, periodDays);

        try {
            // Check if analysis already exists for today
            LocalDate today = LocalDate.now();
//            if (analysisRepository.existsByUserIdAndAnalysisDate(userId, today)) {
//                log.info("📅 Analysis already exists for user {} on {}", userId, today);
//                Optional<CodingAnalysisResult> existingOpt = analysisRepository
//                        .findFirstByUserIdOrderByAnalysisDateDesc(userId);
//
//                if (existingOpt.isPresent()) {
//                    CodingAnalysisResult existing = existingOpt.get();
//                    return buildAnalysisResult(existing);
//                }
//            }

            // Step 1: Aggregate user data
            UserCodingData userData = dataAggregationService.aggregateUserData(userId, periodDays);
            log.info("📊 Aggregated data: {} problems, {} runs, {} submits for user {}",
                    userData.getTotalProblems(), userData.getTotalRuns(), userData.getTotalSubmits(), userId);

            // Check if user has sufficient data for meaningful analysis
            if (userData.getTotalRuns() == 0 && userData.getTotalSubmits() == 0) {
                log.warn("⚠️ No coding activity found for user {} in the last {} days", userId, periodDays);
                return AnalysisResult.builder()
                        .success(false)
                        .errorMessage("No coding activity found for the specified period")
                        .build();
            }

            // Step 2: Perform AI-powered analysis with fallback
            CodingAnalysisResult analysisResult = performAIAnalysis(userData);

            // Step 3: Save results
            CodingAnalysisResult savedResult = analysisRepository.save(analysisResult);
            log.info("✅ Analysis saved with ID: {}", savedResult.getId());

            return buildAnalysisResult(savedResult);

        } catch (JsonProcessingException e) {
            log.error("❌ JSON processing error for user {}: {}", userId, e.getMessage(), e);
            return AnalysisResult.builder()
                    .success(false)
                    .errorMessage("Failed to process analysis data: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("❌ Unexpected error analyzing user {}: {}", userId, e.getMessage(), e);
            return AnalysisResult.builder()
                    .success(false)
                    .errorMessage("Analysis failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Builds AnalysisResult from CodingAnalysisResult entity
     */
    private AnalysisResult buildAnalysisResult(CodingAnalysisResult savedResult) {
        // Extract summary from problemSolvingStyle (which contains the full analysis)
        String summary = extractSummaryFromAnalysis(savedResult.getProblemSolvingStyle());

        return AnalysisResult.builder()
                .success(true)
                .analysisId(savedResult.getId())
                .summary(summary)  // ✅ Now properly populated
                .recommendations(parseRecommendations(savedResult.getImprovementSuggestions()))
                .initialApproachRating(savedResult.getInitialApproachRating() != null ?
                        savedResult.getInitialApproachRating().doubleValue() : null)
                .codeQualityScore(savedResult.getCodeQualityScore() != null ?
                        savedResult.getCodeQualityScore().doubleValue() : null)
                .build();
    }

    /**
     * Extracts a concise summary from the full analysis
     */
    private String extractSummaryFromAnalysis(String fullAnalysis) {
        if (fullAnalysis == null || fullAnalysis.trim().isEmpty()) {
            return "Analysis completed with basic heuristic evaluation.";
        }

        // Extract the first meaningful paragraph as summary
        String[] sections = fullAnalysis.split("\n\n");
        for (String section : sections) {
            if (section.length() > 50 && !section.startsWith("#")) {
                // Clean up formatting and take first 200 characters
                String clean = section.replaceAll("\\*\\*", "").replaceAll("###?\\s*", "").trim();
                if (clean.length() > 200) {
                    return clean.substring(0, 197) + "...";
                }
                return clean;
            }
        }

        return "Comprehensive coding pattern analysis completed based on your recent activity.";
    }

    /**
     * Performs AI-powered analysis with fallback to heuristics
     */
    private CodingAnalysisResult performAIAnalysis(UserCodingData userData) throws JsonProcessingException {
        String aiInsights;
        String aiModelUsed;
        double analysisConfidence;

        // Try AI analysis first
        try {
            log.info("🤖 Requesting AI analysis from Perplexity for user {}", userData.getUserId());
            aiInsights = perplexityService.analyzeUserCodingPatterns(userData);

            if (aiInsights != null && !aiInsights.trim().isEmpty() && !aiInsights.contains("AI analysis temporarily unavailable")) {
                aiModelUsed = "perplexity-ai";
                analysisConfidence = 0.90;
                log.info("✅ Successfully obtained AI analysis for user {}", userData.getUserId());
            } else {
                throw new RuntimeException("AI returned empty or fallback response");
            }
        } catch (Exception e) {
            log.warn("🔄 AI analysis failed for user {}, using heuristic fallback: {}", userData.getUserId(), e.getMessage());
            aiInsights = generateHeuristicAnalysis(userData);
            aiModelUsed = "heuristic-fallback";
            analysisConfidence = 0.65;
        }

        // Extract or calculate metrics
        double approachRating = extractOrCalculateApproachRating(aiInsights, userData);
        double qualityScore = extractOrCalculateQualityScore(aiInsights, userData);

        String problemSolvingStyle = extractOrGenerateProblemSolvingStyle(aiInsights, userData);
        String strengths = extractOrGenerateStrengths(aiInsights, userData);
        String weaknesses = extractOrGenerateWeaknesses(aiInsights, userData);

        Map<String, Object> suggestions = extractOrGenerateImprovementSuggestions(aiInsights, userData);

        return CodingAnalysisResult.builder()
                .userId(userData.getUserId())
                .analysisDate(LocalDate.now())
                .analysisPeriodDays(userData.getAnalysisPeriodDays())
                .totalProblemsAttempted(userData.getTotalProblems())
                .totalRuns(userData.getTotalRuns())
                .totalSubmits(userData.getTotalSubmits())
                .uniqueLanguagesUsed(userData.getLanguagesUsed().size())
                .mostUsedLanguage(userData.getMostUsedLanguage())
                .problemCategories(objectMapper.writeValueAsString(userData.getProblemCategories()))
                .initialApproachRating(BigDecimal.valueOf(approachRating))
                .codeQualityScore(BigDecimal.valueOf(qualityScore))
                .problemSolvingStyle(problemSolvingStyle)
                .strengths(strengths)
                .weaknesses(weaknesses)
                .improvementSuggestions(objectMapper.writeValueAsString(suggestions))
                .aiModelUsed(aiModelUsed)
                .analysisConfidence(BigDecimal.valueOf(analysisConfidence))
                .build();
    }

    /**
     * Generates comprehensive heuristic-based analysis as fallback
     */
    private String generateHeuristicAnalysis(UserCodingData userData) {
        StringBuilder analysis = new StringBuilder();

        analysis.append("## 🎯 **CODING PATTERN ANALYSIS**\n\n");

        // Detailed Problem-Solving Style Analysis
        analysis.append("### **Problem-Solving Approach**\n");
        double runToSubmitRatio = userData.getTotalSubmits() > 0 ?
                (double) userData.getTotalRuns() / userData.getTotalSubmits() : 0;

        if (runToSubmitRatio > 3) {
            analysis.append("You demonstrate a **thorough, iterative approach** to problem-solving. ")
                    .append("With ").append(userData.getTotalRuns()).append(" code executions across ")
                    .append(userData.getTotalSubmits()).append(" submissions (")
                    .append(String.format("%.1fx ratio", runToSubmitRatio))
                    .append("), you clearly prefer to test and refine your solutions before submitting. ")
                    .append("This methodical approach shows strong debugging skills and attention to detail.\n\n");
        } else if (runToSubmitRatio > 1.5) {
            analysis.append("You show a **balanced, confident approach** to coding. ")
                    .append("Your run-to-submit ratio of ")
                    .append(String.format("%.1f", runToSubmitRatio))
                    .append(" suggests you test your code appropriately while maintaining efficiency. ")
                    .append("This indicates good problem-solving intuition.\n\n");
        } else {
            analysis.append("You demonstrate a **direct, confident coding style**. ")
                    .append("With minimal testing iterations before submission, you likely have strong ")
                    .append("initial problem analysis skills and code confidence.\n\n");
        }

        // Activity and Consistency Analysis
        analysis.append("### **Practice Consistency & Volume**\n");
        double dailyAverage = (double) userData.getTotalRuns() / userData.getAnalysisPeriodDays();

        if (userData.getTotalRuns() > 50) {
            analysis.append("**Excellent activity level!** ");
        } else if (userData.getTotalRuns() > 20) {
            analysis.append("**Good practice consistency.** ");
        } else if (userData.getTotalRuns() > 5) {
            analysis.append("**Moderate engagement level.** ");
        } else {
            analysis.append("**Low activity detected.** ");
        }

        analysis.append("Over the past ").append(userData.getAnalysisPeriodDays())
                .append(" days, you've executed code ").append(userData.getTotalRuns())
                .append(" times (").append(String.format("%.1f per day average", dailyAverage))
                .append(") across ").append(userData.getTotalProblems())
                .append(" unique problems.\n\n");

        // Technical Breadth Analysis
        analysis.append("### **Technical Versatility**\n");
        Set<String> languages = userData.getLanguagesUsed();
        if (languages.size() > 2) {
            analysis.append("**Strong multi-language proficiency!** You've demonstrated versatility across ")
                    .append(languages.size()).append(" programming languages: ")
                    .append(String.join(", ", languages)).append(". ");
            analysis.append("This breadth shows adaptability and comprehensive programming knowledge. ");
            analysis.append("Your primary focus on ").append(userData.getMostUsedLanguage())
                    .append(" while maintaining other languages shows balanced skill development.\n\n");
        } else if (languages.size() > 1) {
            analysis.append("**Good language diversity.** You're working with ")
                    .append(String.join(" and ", languages))
                    .append(", showing flexibility in your technical approach. ");
            analysis.append("Consider exploring additional languages to broaden your problem-solving toolkit.\n\n");
        } else {
            analysis.append("**Focused specialization** in ").append(userData.getMostUsedLanguage())
                    .append(". While deep expertise is valuable, exploring other languages like Python, Java, or C++ ")
                    .append("could enhance your problem-solving perspectives and career opportunities.\n\n");
        }

        // Problem Category Analysis
        analysis.append("### **Problem Domain Coverage**\n");
        Map<String, Long> categories = userData.getProblemCategories();
        if (categories.size() > 4) {
            analysis.append("**Excellent problem diversity!** You're tackling ")
                    .append(categories.size()).append(" different problem categories: ");
            categories.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .forEach(entry -> analysis.append(entry.getKey())
                            .append(" (").append(entry.getValue()).append("), "));
            analysis.setLength(analysis.length() - 2); // Remove last comma
            analysis.append(". This breadth demonstrates strong algorithmic thinking across multiple domains.\n\n");
        } else if (categories.size() > 2) {
            analysis.append("**Good problem variety.** You're working on ")
                    .append(categories.size()).append(" categories, showing solid foundational coverage. ");
            analysis.append("Consider expanding into areas like Dynamic Programming, Graph algorithms, or System Design for comprehensive growth.\n\n");
        } else {
            analysis.append("**Limited problem scope detected.** Expanding beyond your current focus areas will significantly enhance your algorithmic skills. ");
            analysis.append("Target categories like Arrays, Strings, Trees, Graphs, and Dynamic Programming for well-rounded development.\n\n");
        }

        // Performance and Efficiency Insights
        analysis.append("### **Code Quality Indicators**\n");
        double submitRatio = userData.getTotalProblems() > 0 ?
                (double) userData.getTotalSubmits() / userData.getTotalProblems() : 0;

        if (submitRatio > 0.8) {
            analysis.append("**High solution completion rate** (")
                    .append(String.format("%.0f%%", submitRatio * 100))
                    .append(") indicates strong problem-solving persistence and code quality. ");
        } else if (submitRatio > 0.5) {
            analysis.append("**Decent completion rate** (")
                    .append(String.format("%.0f%%", submitRatio * 100))
                    .append(") shows consistent effort, with room for improvement in solution finalization. ");
        } else {
            analysis.append("**Low submission rate** suggests opportunities to focus on completing solutions rather than just exploring approaches. ");
        }

        analysis.append("Your coding patterns suggest you're ");
        if (userData.getTotalRuns() > userData.getTotalSubmits() * 4) {
            analysis.append("very thorough in testing but could benefit from more decisive solution implementation.\n\n");
        } else {
            analysis.append("balancing exploration with practical solution delivery effectively.\n\n");
        }

        // Personalized Growth Recommendations
        analysis.append("### **🚀 Strategic Development Path**\n");
        analysis.append("Based on your coding patterns, here's your personalized growth strategy:\n\n");

        // Immediate Focus Areas
        analysis.append("**Immediate Priorities:**\n");
        if (languages.size() == 1) {
            analysis.append("• **Language Expansion**: Add Python or Java to your toolkit for broader opportunities\n");
        }
        if (categories.size() <= 2) {
            analysis.append("• **Algorithm Diversity**: Practice Graph traversal and Dynamic Programming problems\n");
        }
        if (userData.getTotalProblems() < 10) {
            analysis.append("• **Volume Building**: Target 15-20 problems per month for consistent improvement\n");
        }
        if (submitRatio < 0.5) {
            analysis.append("• **Solution Completion**: Focus on finishing and submitting more solutions\n");
        }

        // Advanced Development
        analysis.append("\n**Advanced Development:**\n");
        analysis.append("• **Complexity Analysis**: Study Big-O notation for optimization insights\n");
        analysis.append("• **Design Patterns**: Learn common algorithmic patterns and when to apply them\n");
        analysis.append("• **System Design**: Progress to architectural thinking for senior-level skills\n");
        analysis.append("• **Competitive Programming**: Join contests for rapid skill acceleration\n\n");

        // Confidence and Strengths Summary
        analysis.append("### **💪 Key Strengths Identified**\n");
        List<String> strengths = new ArrayList<>();

        if (userData.getTotalRuns() > 20) {
            strengths.add("Consistent practice habits");
        }
        if (runToSubmitRatio > 2) {
            strengths.add("Thorough code testing approach");
        }
        if (languages.size() > 1) {
            strengths.add("Multi-language adaptability");
        }
        if (categories.size() > 3) {
            strengths.add("Diverse problem-solving experience");
        }
        if (submitRatio > 0.6) {
            strengths.add("Strong solution completion rate");
        }

        if (strengths.isEmpty()) {
            strengths.add("Foundational coding engagement");
            strengths.add("Growth-oriented learning approach");
        }

        analysis.append("Your analysis reveals these core strengths: ")
                .append(String.join(", ", strengths))
                .append(". Building on these foundations will accelerate your development significantly.\n\n");

        analysis.append("---\n");
        analysis.append("*This analysis uses advanced heuristic evaluation of your coding patterns. ")
                .append("For AI-powered insights with deeper code analysis, ensure your API configuration is active.*");

        return analysis.toString();
    }


    /**
     * Extracts approach rating from AI response or calculates heuristically
     */
    private double extractOrCalculateApproachRating(String aiInsights, UserCodingData userData) {
        // Try to extract rating from AI response
        try {
            if (aiInsights.contains("Initial Approach Rating") || aiInsights.contains("approach rating")) {
                // Use regex or string parsing to extract rating
                String[] lines = aiInsights.split("\n");
                for (String line : lines) {
                    if (line.toLowerCase().contains("rating") && line.matches(".*\\b[1-5](\\.\\d)?\\b.*")) {
                        String rating = line.replaceAll(".*\\b([1-5](?:\\.\\d)?)\\b.*", "$1");
                        return Double.parseDouble(rating);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract rating from AI response, using calculation");
        }

        return calculateApproachRating(userData);
    }

    /**
     * Extracts quality score from AI response or calculates heuristically
     */
    private double extractOrCalculateQualityScore(String aiInsights, UserCodingData userData) {
        try {
            if (aiInsights.contains("Code Quality Score") || aiInsights.contains("quality score")) {
                String[] lines = aiInsights.split("\n");
                for (String line : lines) {
                    if (line.toLowerCase().contains("quality") && line.matches(".*\\b[1-5](\\.\\d)?\\b.*")) {
                        String score = line.replaceAll(".*\\b([1-5](?:\\.\\d)?)\\b.*", "$1");
                        return Double.parseDouble(score);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract quality score from AI response, using calculation");
        }

        return calculateQualityScore(userData);
    }

    /**
     * Extracts problem-solving style from AI or generates heuristically
     */
    private String extractOrGenerateProblemSolvingStyle(String aiInsights, UserCodingData userData) {
        if (aiInsights.contains("Problem-Solving Style") || aiInsights.contains("problem solving")) {
            return extractSectionFromAI(aiInsights, "Problem-Solving Style");
        }
        return generateProblemSolvingStyle(userData);
    }

    /**
     * Extracts strengths from AI or generates heuristically
     */
    private String extractOrGenerateStrengths(String aiInsights, UserCodingData userData) {
        if (aiInsights.contains("Strengths") || aiInsights.contains("Key Strengths")) {
            return extractSectionFromAI(aiInsights, "Strengths");
        }
        return generateStrengths(userData);
    }

    /**
     * Extracts weaknesses from AI or generates heuristically
     */
    private String extractOrGenerateWeaknesses(String aiInsights, UserCodingData userData) {
        if (aiInsights.contains("Weaknesses") || aiInsights.contains("Areas for Improvement")) {
            return extractSectionFromAI(aiInsights, "Areas for Improvement");
        }
        return generateWeaknesses(userData);
    }

    /**
     * Extracts improvement suggestions from AI or generates heuristically
     */
    private Map<String, Object> extractOrGenerateImprovementSuggestions(String aiInsights, UserCodingData userData) {
        if (aiInsights.contains("Recommendations") || aiInsights.contains("improvement")) {
            try {
                String recommendations = extractSectionFromAI(aiInsights, "Recommendations");
                List<String> focusAreas = Arrays.stream(recommendations.split("\\n"))
                        .filter(line -> line.trim().startsWith("•") || line.trim().startsWith("-") || line.trim().startsWith("*"))
                        .map(line -> line.replaceAll("^[•\\-\\*]\\s*", "").trim())
                        .collect(Collectors.toList());

                Map<String, Object> suggestions = new HashMap<>();
                suggestions.put("focus_areas", focusAreas);
                suggestions.put("next_steps", focusAreas); // Use same for now
                return suggestions;
            } catch (Exception e) {
                log.debug("Could not extract suggestions from AI response");
            }
        }
        return generateImprovementSuggestions(userData);
    }

    // Include all the helper methods from the previous implementation
    private double calculateApproachRating(UserCodingData userData) {
        double rating = 3.0;
        if (userData.getTotalRuns() > 10) rating += 0.5;
        if (userData.getTotalSubmits() > 5) rating += 0.3;
        if (userData.getTotalProblems() > 5) rating += 0.4;
        if (userData.getLanguagesUsed().size() > 1) rating += 0.2;
        if (userData.getProblemCategories().size() > 3) rating += 0.2;
        return Math.min(5.0, Math.max(1.0, rating));
    }

    private double calculateQualityScore(UserCodingData userData) {
        double score = 3.5;
        if (userData.getTotalSubmits() > 0) {
            double ratio = (double) userData.getTotalRuns() / userData.getTotalSubmits();
            if (ratio <= 2) score += 1.0;
            else if (ratio <= 3) score += 0.5;
            else if (ratio > 6) score -= 0.3;
        }
        if (userData.getTotalProblems() > 0 && userData.getTotalSubmits() > 0) {
            double submitRatio = (double) userData.getTotalSubmits() / userData.getTotalProblems();
            if (submitRatio > 0.7) score += 0.3;
        }
        return Math.min(5.0, Math.max(1.0, score));
    }

    private String generateProblemSolvingStyle(UserCodingData userData) {
        StringBuilder style = new StringBuilder();

        if (userData.getTotalRuns() > userData.getTotalSubmits() * 2) {
            style.append("Iterative problem solver who thoroughly tests code before submission. ");
        } else {
            style.append("Confident problem solver with a focused and efficient approach. ");
        }

        if (userData.getLanguagesUsed().size() > 1) {
            style.append("Demonstrates versatility by using multiple programming languages. ");
        }

        if (userData.getProblemCategories().size() > 2) {
            style.append("Shows breadth in problem-solving by tackling diverse categories. ");
        }

        return style.toString().trim();
    }

    private String generateStrengths(UserCodingData userData) {
        List<String> strengths = new ArrayList<>();
        strengths.add("Active coding practice");

        if (userData.getTotalRuns() > 5) {
            strengths.add("Regular practice habits");
        }
        if (userData.getLanguagesUsed().size() > 1) {
            strengths.add("Language versatility");
        }
        if (userData.getProblemCategories().size() > 3) {
            strengths.add("Diverse problem-solving approach");
        }
        if (userData.getTotalSubmits() > userData.getTotalRuns() * 0.3) {
            strengths.add("Good solution completion rate");
        }

        return String.join(", ", strengths);
    }

    private String generateWeaknesses(UserCodingData userData) {
        List<String> weaknesses = new ArrayList<>();

        if (userData.getAnalysisPeriodDays() < 14) {
            weaknesses.add("Limited analysis period");
        }
        if (userData.getProblemCategories().size() <= 2) {
            weaknesses.add("Need more diverse problem categories");
        }
        if (userData.getLanguagesUsed().size() == 1) {
            weaknesses.add("Could benefit from exploring multiple programming languages");
        }
        if (userData.getTotalRuns() > userData.getTotalSubmits() * 5) {
            weaknesses.add("High run-to-submit ratio suggests room for improvement in solution confidence");
        }

        if (weaknesses.isEmpty()) {
            weaknesses.add("Areas for continued growth and learning");
        }

        return String.join(", ", weaknesses);
    }

    private Map<String, Object> generateImprovementSuggestions(UserCodingData userData) {
        Map<String, Object> suggestions = new HashMap<>();

        List<String> focusAreas = new ArrayList<>();
        List<String> nextSteps = new ArrayList<>();
        List<String> resources = new ArrayList<>();

        // Dynamic focus areas based on actual patterns
        if (userData.getProblemCategories().size() <= 2) {
            focusAreas.add("Expand into new problem categories (Graphs, Dynamic Programming, Trees)");
            resources.add("LeetCode problem categories guide");
        }

        if (userData.getLanguagesUsed().size() == 1) {
            focusAreas.add("Learn a second programming language (Python/Java/C++)");
            resources.add("Multi-language algorithm practice");
        }

        double runToSubmitRatio = userData.getTotalSubmits() > 0 ?
                (double) userData.getTotalRuns() / userData.getTotalSubmits() : 0;

        if (runToSubmitRatio > 4) {
            focusAreas.add("Improve initial problem analysis to reduce testing iterations");
            resources.add("Problem-solving frameworks and pattern recognition");
        } else if (runToSubmitRatio < 1.5) {
            focusAreas.add("Increase code testing and edge case consideration");
        }

        // Smart next steps
        if (userData.getTotalProblems() < 10) {
            nextSteps.add("Complete 15-20 problems in the next month");
            nextSteps.add("Focus on fundamental data structures (Arrays, LinkedLists, Stacks)");
        } else if (userData.getTotalProblems() < 50) {
            nextSteps.add("Progress to medium-difficulty problems");
            nextSteps.add("Study time and space complexity analysis");
        } else {
            nextSteps.add("Tackle hard problems and optimize existing solutions");
            nextSteps.add("Explore system design concepts");
        }

        nextSteps.add("Join coding competitions or daily challenges");
        nextSteps.add("Review and optimize your most challenging solutions");

        suggestions.put("focus_areas", focusAreas);
        suggestions.put("next_steps", nextSteps);
        suggestions.put("resources", resources);
        suggestions.put("timeline", "2-4 weeks for immediate improvements, 2-3 months for advanced skills");

        return suggestions;
    }

    /**
     * Helper method to extract sections from AI response
     */
    private String extractSectionFromAI(String aiResponse, String section) {
        try {
            String[] lines = aiResponse.split("\n");
            StringBuilder result = new StringBuilder();
            boolean inSection = false;

            for (String line : lines) {
                if (line.contains(section)) {
                    inSection = true;
                    continue;
                }
                if (inSection && line.startsWith("**") && !line.contains(section)) {
                    break;
                }
                if (inSection && !line.trim().isEmpty()) {
                    result.append(line.trim()).append(" ");
                }
            }

            return result.toString().trim();
        } catch (Exception e) {
            log.warn("Failed to extract section {} from AI response", section);
            return "AI analysis available - see full details";
        }
    }

    /**
     * Parses recommendations JSON string back to List
     */
    @SuppressWarnings("unchecked")
    private List<String> parseRecommendations(String suggestionsJson) {
        if (suggestionsJson == null || suggestionsJson.trim().isEmpty()) {
            return Arrays.asList("Continue practicing regularly", "Focus on problem-solving patterns");
        }

        try {
            Map<String, Object> suggestions = objectMapper.readValue(suggestionsJson, Map.class);
            Object focusAreas = suggestions.get("focus_areas");
            if (focusAreas instanceof List) {
                return (List<String>) focusAreas;
            }
        } catch (Exception e) {
            log.warn("Failed to parse recommendations JSON: {}", e.getMessage());
        }

        return Arrays.asList("Continue practicing regularly", "Focus on problem-solving patterns");
    }
}
