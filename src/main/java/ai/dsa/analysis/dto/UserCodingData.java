package ai.dsa.analysis.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Builder
public class UserCodingData {
    private String userId;
    private int totalProblems;
    private int totalRuns;
    private int totalSubmits;
    private Set<String> languagesUsed;
    private String mostUsedLanguage;
    private Map<String, Long> problemCategories;
    private List<String> recentCodeSamples;
    private int analysisPeriodDays;
}
