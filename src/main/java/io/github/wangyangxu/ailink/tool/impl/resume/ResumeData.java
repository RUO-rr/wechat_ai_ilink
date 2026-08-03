package io.github.wangyangxu.ailink.tool.impl.resume;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 简历数据模型——用于在 LLM 和简历生成操作之间传递结构化的简历内容。
 * <p>
 * 参考 resume-builder-cn 的 intake_schema.md 和 candidate_evidence_schema.md 设计，
 * 按候选人类型（学生/职场/科研/转行）支持不同信息密度。
 * <p>
 * 所有嵌套类标注了 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 和
 * {@code @JsonAlias}，以兼容 LLM 输出的 JSON 字段命名差异（如 skill 的 "name" 映射到 category）。
 */
public class ResumeData {

    // ========= 基本设置 =========
    /** 候选人类型：student | experienced | researcher | career_changer */
    private String candidateType = "student";
    /** 简历语言：zh | en | bilingual */
    private String language = "zh";
    /** 简历类型：internship | corporate | research | academic_cv */
    private String resumeType = "corporate";
    /** 目标岗位 */
    @JsonAlias({"target_role", "targetPosition", "desired_position"})
    private String targetRole;
    /** 目标公司/机构 */
    @JsonAlias({"target_organization", "targetCompany", "target_company"})
    private String targetOrganization;
    /** 目标行业 */
    @JsonAlias({"target_industry", "targetIndustry"})
    private String targetIndustry;

    // ========= 个人信息 =========
    private String name;
    private String phone;
    private String email;
    @JsonAlias({"city", "address"})
    private String location;
    private String linkedin;
    private String github;
    private String portfolio;
    private String wechat;
    private String gender;
    @JsonAlias({"native_place", "hometown", "origin"})
    private String nativePlace;
    @JsonAlias({"date_of_birth", "dob", "birthday"})
    private String dateOfBirth;
    @JsonAlias({"political_affiliation", "politics"})
    private String politicalAffiliation;

    // ========= 职业概述 =========
    @JsonAlias({"professional_summary", "profile", "objective"})
    private String summary;
    /** 求职意向 */
    @JsonAlias({"career_objective", "job_intention", "intention"})
    private String careerObjective;

    // ========= 教育背景 =========
    private List<Education> educationList = new ArrayList<>();

    // ========= 工作经历 =========
    private List<Experience> workExperienceList = new ArrayList<>();

    // ========= 项目经历 =========
    private List<Experience> projectList = new ArrayList<>();

    // ========= 实习经历 =========
    private List<Experience> internshipList = new ArrayList<>();

    // ========= 技能 =========
    private List<SkillCategory> skillList = new ArrayList<>();

    // ========= 论文发表 =========
    private List<Publication> publicationList = new ArrayList<>();

    // ========= 专利 =========
    private List<Patent> patentList = new ArrayList<>();

    // ========= 获奖/荣誉 =========
    private List<Award> awardList = new ArrayList<>();

    // ========= 语言能力 =========
    private List<Language> languageList = new ArrayList<>();

    // ========= 证书 =========
    private List<String> certificateList = new ArrayList<>();

    // ========= 输出设置 =========
    /** 期望页数限制 */
    private int pageLimit = 1;
    /** 是否输出 ATS 友好版本 */
    private boolean atsCompatible = true;
    /** 额外说明 */
    private String notes;

    // ========= 内部嵌套类 =========

    /** 教育经历 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Education {
        @JsonAlias({"school", "university", "college"})
        private String institution;
        @JsonAlias({"department", "school"})
        private String college;
        private String degree;
        @JsonAlias({"field", "field_of_study", "major_name"})
        private String major;
        private String location;
        @JsonAlias({"start_date", "start"})
        private String startDate;
        @JsonAlias({"end_date", "end", "graduation_date"})
        private String endDate;
        @JsonAlias({"GPA", "gpa_scale"})
        private String gpa;
        @JsonAlias({"rank", "class_rank"})
        private String ranking;
        private String thesis;
        private String supervisor;
        @JsonAlias({"relevant_courses", "courses"})
        private List<String> relevantCourses = new ArrayList<>();
        private String honors;
        private String notes;

        public String getInstitution() { return institution; }
        public void setInstitution(String institution) { this.institution = institution; }
        public String getCollege() { return college; }
        public void setCollege(String college) { this.college = college; }
        public String getDegree() { return degree; }
        public void setDegree(String degree) { this.degree = degree; }
        public String getMajor() { return major; }
        public void setMajor(String major) { this.major = major; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }
        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }
        public String getGpa() { return gpa; }
        public void setGpa(String gpa) { this.gpa = gpa; }
        public String getRanking() { return ranking; }
        public void setRanking(String ranking) { this.ranking = ranking; }
        public String getThesis() { return thesis; }
        public void setThesis(String thesis) { this.thesis = thesis; }
        public String getSupervisor() { return supervisor; }
        public void setSupervisor(String supervisor) { this.supervisor = supervisor; }
        public List<String> getRelevantCourses() { return relevantCourses; }
        public void setRelevantCourses(List<String> relevantCourses) { this.relevantCourses = relevantCourses; }
        public String getHonors() { return honors; }
        public void setHonors(String honors) { this.honors = honors; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    /** 经历（工作/项目/实习共用） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Experience {
        @JsonAlias({"company", "employer", "org"})
        private String organization;
        @JsonAlias({"title", "position", "job_title"})
        private String role;
        private String location;
        @JsonAlias({"start_date", "start"})
        private String startDate;
        @JsonAlias({"end_date", "end"})
        private String endDate;
        @JsonAlias({"employment_type", "type"})
        private String employmentType;
        @JsonAlias({"team_size", "teamSize", "team_scope"})
        private String scope;
        private List<String> responsibilities = new ArrayList<>();
        @JsonAlias({"bullet_points", "bullets", "achievements", "highlights"})
        private List<String> bulletPoints = new ArrayList<>();
        private String methods;
        private String tools;
        private String results;
        private String impact;
        private String notes;

        public String getOrganization() { return organization; }
        public void setOrganization(String organization) { this.organization = organization; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }
        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }
        public String getEmploymentType() { return employmentType; }
        public void setEmploymentType(String employmentType) { this.employmentType = employmentType; }
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
        public List<String> getResponsibilities() { return responsibilities; }
        public void setResponsibilities(List<String> responsibilities) { this.responsibilities = responsibilities; }
        public List<String> getBulletPoints() { return bulletPoints; }
        public void setBulletPoints(List<String> bulletPoints) { this.bulletPoints = bulletPoints; }
        public String getMethods() { return methods; }
        public void setMethods(String methods) { this.methods = methods; }
        public String getTools() { return tools; }
        public void setTools(String tools) { this.tools = tools; }
        public String getResults() { return results; }
        public void setResults(String results) { this.results = results; }
        public String getImpact() { return impact; }
        public void setImpact(String impact) { this.impact = impact; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    /** 技能分类 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillCategory {
        @JsonAlias({"name", "category_name", "type"})
        private String category;
        @JsonAlias({"items", "skill_list", "skillItems"})
        private List<String> skills = new ArrayList<>();

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public List<String> getSkills() { return skills; }
        public void setSkills(List<String> skills) { this.skills = skills; }
    }

    /** 论文发表 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Publication {
        private String title;
        private String authors;
        private String journal;
        private String year;
        private String volume;
        private String issue;
        private String pages;
        private String doi;
        @JsonAlias({"publication_status", "pub_status"})
        private String status;
        private String notes;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getAuthors() { return authors; }
        public void setAuthors(String authors) { this.authors = authors; }
        public String getJournal() { return journal; }
        public void setJournal(String journal) { this.journal = journal; }
        public String getYear() { return year; }
        public void setYear(String year) { this.year = year; }
        public String getVolume() { return volume; }
        public void setVolume(String volume) { this.volume = volume; }
        public String getIssue() { return issue; }
        public void setIssue(String issue) { this.issue = issue; }
        public String getPages() { return pages; }
        public void setPages(String pages) { this.pages = pages; }
        public String getDoi() { return doi; }
        public void setDoi(String doi) { this.doi = doi; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    /** 专利 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Patent {
        private String title;
        @JsonAlias({"inventor", "authors"})
        private String inventors;
        @JsonAlias({"patent_number", "patentNo", "patent_id"})
        private String patentNumber;
        @JsonAlias({"application_date", "apply_date"})
        private String applicationDate;
        @JsonAlias({"grant_date", "grantDate"})
        private String grantDate;
        @JsonAlias({"patent_status"})
        private String status;
        private String notes;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getInventors() { return inventors; }
        public void setInventors(String inventors) { this.inventors = inventors; }
        public String getPatentNumber() { return patentNumber; }
        public void setPatentNumber(String patentNumber) { this.patentNumber = patentNumber; }
        public String getApplicationDate() { return applicationDate; }
        public void setApplicationDate(String applicationDate) { this.applicationDate = applicationDate; }
        public String getGrantDate() { return grantDate; }
        public void setGrantDate(String grantDate) { this.grantDate = grantDate; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    /** 获奖 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Award {
        @JsonAlias({"award_name", "name"})
        private String title;
        @JsonAlias({"issuer", "awarding_body", "institution"})
        private String organization;
        private String date;
        @JsonAlias({"award_level", "level"})
        private String level;
        private String notes;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getOrganization() { return organization; }
        public void setOrganization(String organization) { this.organization = organization; }
        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    /** 语言能力 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Language {
        @JsonAlias({"lang", "name"})
        private String language;
        @JsonAlias({"read", "reading_level"})
        private String reading;
        @JsonAlias({"write", "writing_level"})
        private String writing;
        @JsonAlias({"speak", "speaking_level", "spoken"})
        private String speaking;
        @JsonAlias({"cert", "certificate"})
        private String certification;

        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public String getReading() { return reading; }
        public void setReading(String reading) { this.reading = reading; }
        public String getWriting() { return writing; }
        public void setWriting(String writing) { this.writing = writing; }
        public String getSpeaking() { return speaking; }
        public void setSpeaking(String speaking) { this.speaking = speaking; }
        public String getCertification() { return certification; }
        public void setCertification(String certification) { this.certification = certification; }
    }

    // ========= ResumeData 的 getters/setters =========

    public String getCandidateType() { return candidateType; }
    public void setCandidateType(String candidateType) { this.candidateType = candidateType; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getResumeType() { return resumeType; }
    public void setResumeType(String resumeType) { this.resumeType = resumeType; }
    public String getTargetRole() { return targetRole; }
    public void setTargetRole(String targetRole) { this.targetRole = targetRole; }
    public String getTargetOrganization() { return targetOrganization; }
    public void setTargetOrganization(String targetOrganization) { this.targetOrganization = targetOrganization; }
    public String getTargetIndustry() { return targetIndustry; }
    public void setTargetIndustry(String targetIndustry) { this.targetIndustry = targetIndustry; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getLinkedin() { return linkedin; }
    public void setLinkedin(String linkedin) { this.linkedin = linkedin; }
    public String getGithub() { return github; }
    public void setGithub(String github) { this.github = github; }
    public String getPortfolio() { return portfolio; }
    public void setPortfolio(String portfolio) { this.portfolio = portfolio; }
    public String getWechat() { return wechat; }
    public void setWechat(String wechat) { this.wechat = wechat; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getNativePlace() { return nativePlace; }
    public void setNativePlace(String nativePlace) { this.nativePlace = nativePlace; }
    public String getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(String dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getPoliticalAffiliation() { return politicalAffiliation; }
    public void setPoliticalAffiliation(String politicalAffiliation) { this.politicalAffiliation = politicalAffiliation; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getCareerObjective() { return careerObjective; }
    public void setCareerObjective(String careerObjective) { this.careerObjective = careerObjective; }

    public List<Education> getEducationList() { return educationList; }
    public void setEducationList(List<Education> educationList) { this.educationList = educationList; }
    public List<Experience> getWorkExperienceList() { return workExperienceList; }
    public void setWorkExperienceList(List<Experience> workExperienceList) { this.workExperienceList = workExperienceList; }
    public List<Experience> getProjectList() { return projectList; }
    public void setProjectList(List<Experience> projectList) { this.projectList = projectList; }
    public List<Experience> getInternshipList() { return internshipList; }
    public void setInternshipList(List<Experience> internshipList) { this.internshipList = internshipList; }
    public List<SkillCategory> getSkillList() { return skillList; }
    public void setSkillList(List<SkillCategory> skillList) { this.skillList = skillList; }
    public List<Publication> getPublicationList() { return publicationList; }
    public void setPublicationList(List<Publication> publicationList) { this.publicationList = publicationList; }
    public List<Patent> getPatentList() { return patentList; }
    public void setPatentList(List<Patent> patentList) { this.patentList = patentList; }
    public List<Award> getAwardList() { return awardList; }
    public void setAwardList(List<Award> awardList) { this.awardList = awardList; }
    public List<Language> getLanguageList() { return languageList; }
    public void setLanguageList(List<Language> languageList) { this.languageList = languageList; }
    public List<String> getCertificateList() { return certificateList; }
    public void setCertificateList(List<String> certificateList) { this.certificateList = certificateList; }

    public int getPageLimit() { return pageLimit; }
    public void setPageLimit(int pageLimit) { this.pageLimit = pageLimit; }
    public boolean isAtsCompatible() { return atsCompatible; }
    public void setAtsCompatible(boolean atsCompatible) { this.atsCompatible = atsCompatible; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
