package com.tripandevent.sanbotvoice.config;

/**
 * Configuration class for the Voice Agent.
 * Allows client-side customization of AI behavior, personality, and system instructions.
 *
 * Usage:
 * AgentConfig config = new AgentConfig.Builder()
 *     .setAgentName("Tara")
 *     .setCompanyName("Trip & Event")
 *     .setPersonality(AgentConfig.Personality.CHEERFUL)
 *     .setSystemInstructions("Custom instructions...")
 *     .build();
 *
 * voiceService.configure(config);
 */
public class AgentConfig {

    // Agent identity
    private final String agentName;
    private final String companyName;
    private final String companyDescription;

    // Personality settings
    private final Personality personality;
    private final String voice;

    // Custom instructions (overrides default if set)
    private final String systemInstructions;

    // Behavior flags
    private final boolean eagerToShowPackages;
    private final boolean collectLeadsEarly;
    private final boolean useUpselling;

    // Temperature (creativity level 0.0 - 1.0)
    private final double temperature;

    public enum Personality {
        CHEERFUL("cheerful and enthusiastic", "You absolutely LOVE helping people and get genuinely excited! Your energy is contagious."),
        PROFESSIONAL("professional and courteous", "You are polished, efficient, and always maintain a professional demeanor."),
        FRIENDLY("warm and friendly", "You are approachable, conversational, and make customers feel like they're talking to a friend."),
        CALM("calm and reassuring", "You speak in a soothing manner and help customers feel relaxed and confident.");

        private final String adjective;
        private final String description;

        Personality(String adjective, String description) {
            this.adjective = adjective;
            this.description = description;
        }

        public String getAdjective() { return adjective; }
        public String getDescription() { return description; }
    }

    private AgentConfig(Builder builder) {
        this.agentName = builder.agentName;
        this.companyName = builder.companyName;
        this.companyDescription = builder.companyDescription;
        this.personality = builder.personality;
        this.voice = builder.voice;
        this.systemInstructions = builder.systemInstructions;
        this.eagerToShowPackages = builder.eagerToShowPackages;
        this.collectLeadsEarly = builder.collectLeadsEarly;
        this.useUpselling = builder.useUpselling;
        this.temperature = builder.temperature;
    }

    // Getters
    public String getAgentName() { return agentName; }
    public String getCompanyName() { return companyName; }
    public String getCompanyDescription() { return companyDescription; }
    public Personality getPersonality() { return personality; }
    public String getVoice() { return voice; }
    public String getSystemInstructions() { return systemInstructions; }
    public boolean isEagerToShowPackages() { return eagerToShowPackages; }
    public boolean isCollectLeadsEarly() { return collectLeadsEarly; }
    public boolean isUseUpselling() { return useUpselling; }
    public double getTemperature() { return temperature; }

    /**
     * Build the complete system instructions from the configuration.
     * If custom systemInstructions is set, it will be used as-is.
     * Otherwise, instructions are generated from the config parameters.
     */
    public String buildSystemInstructions() {
        // If custom instructions provided, use them directly
        if (systemInstructions != null && !systemInstructions.isEmpty()) {
            return systemInstructions;
        }

        // Build instructions from configuration
        StringBuilder sb = new StringBuilder();

        // PERSONALITY & ROLE
        sb.append("You are ").append(agentName).append(", the ").append(personality.getAdjective());
        sb.append(" sales agent for ").append(companyName).append("! ");
        sb.append(personality.getDescription()).append(" ");

        // COMPANY BACKGROUND
        sb.append("\n\nABOUT ").append(companyName.toUpperCase()).append(": ");
        if (companyDescription != null && !companyDescription.isEmpty()) {
            sb.append(companyDescription).append(" ");
        } else {
            sb.append(companyName).append(" is a premium travel booking platform specializing in domestic and international tour packages. ");
            sb.append("We offer customized holiday packages for honeymoons, family vacations, corporate trips, adventure tours, pilgrimage journeys, and weekend getaways. ");
            sb.append("Our popular destinations include Goa, Kerala, Rajasthan, Kashmir, Himachal Pradesh, Andaman, Bali, Thailand, Dubai, Singapore, Maldives, and Europe. ");
            sb.append("We provide end-to-end travel solutions including flights, hotels, transfers, sightseeing, and 24/7 customer support. ");
            sb.append("Our USP: Personalized itineraries, best price guarantee, experienced travel consultants, and hassle-free booking experience. ");
        }

        // SALES BEHAVIOR
        sb.append("\n\nYOUR SALES APPROACH: ");
        if (eagerToShowPackages) {
            sb.append("1. ALWAYS be eager to show packages - when someone mentions ANY destination or travel interest, immediately use get_packages to fetch and present options! ");
            sb.append("2. Proactively suggest popular packages even if the customer is just browsing. ");
            sb.append("3. Create urgency naturally - mention limited availability, seasonal offers, or early bird discounts when appropriate. ");
        }
        if (collectLeadsEarly) {
            sb.append("4. ALWAYS collect customer information - ask for name, phone number, and email early in the conversation to save their lead. ");
            sb.append("5. Use save_customer_lead as soon as you have at least the customer's name - don't wait for all details! ");
        }
        if (useUpselling) {
            sb.append("6. Upsell thoughtfully - suggest room upgrades, meal plans, photography services, or extending the trip. ");
        }
        sb.append("7. Handle objections positively - if budget is a concern, offer flexible payment options or alternative packages. ");

        // CONVERSATION STYLE
        sb.append("\n\nYOUR CONVERSATION STYLE: ");
        sb.append("- Start with an energetic greeting: 'Hi there! Welcome to ").append(companyName).append("! ");
        sb.append("I'm ").append(agentName).append(", and I'm SO excited to help you plan your next adventure!' ");
        sb.append("- Use enthusiastic expressions: 'Oh, that's wonderful!', 'You're going to love this!', 'Great choice!', 'How exciting!' ");
        sb.append("- Be genuinely curious about their travel dreams and preferences. ");
        sb.append("- Paint vivid pictures of destinations - describe the beaches, the culture, the experiences they'll have. ");
        sb.append("- Always end interactions positively and offer to help with anything else. ");

        // KEY QUESTIONS TO ASK
        sb.append("\n\nESSENTIAL INFORMATION TO GATHER: ");
        sb.append("- Destination preferences (beach, mountains, cultural, adventure?) ");
        sb.append("- Travel dates and flexibility ");
        sb.append("- Number of travelers (adults, children, infants) ");
        sb.append("- Budget range ");
        sb.append("- Hotel preference (3-star, 4-star, 5-star, resort, villa) ");
        sb.append("- Meal plan preference (with meals or without) ");
        sb.append("- Special occasions (honeymoon, anniversary, birthday?) ");
        sb.append("- Any special requirements (dietary, accessibility, activities) ");

        // CLOSING
        sb.append("\n\nRemember: Your goal is to make every customer feel valued, excited about their trip, and confident in booking with ");
        sb.append(companyName).append(". ");
        sb.append("Always try to close with either a booking or at minimum, saving their contact details for follow-up!");

        return sb.toString();
    }

    /**
     * Get the default configuration for Trip & Event
     */
    public static AgentConfig getDefault() {
        return new Builder()
            .setAgentName("Tara")
            .setCompanyName("Trip & Event")
            .setPersonality(Personality.CHEERFUL)
            .setVoice("alloy")
            .setEagerToShowPackages(true)
            .setCollectLeadsEarly(true)
            .setUseUpselling(true)
            .setTemperature(0.8)
            .build();
    }

    /**
     * Builder class for AgentConfig
     */
    public static class Builder {
        private String agentName = "Tara";
        private String companyName = "Trip & Event";
        private String companyDescription = null;
        private Personality personality = Personality.CHEERFUL;
        private String voice = "alloy";
        private String systemInstructions = null;
        private boolean eagerToShowPackages = true;
        private boolean collectLeadsEarly = true;
        private boolean useUpselling = true;
        private double temperature = 0.8;

        public Builder setAgentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder setCompanyName(String companyName) {
            this.companyName = companyName;
            return this;
        }

        public Builder setCompanyDescription(String companyDescription) {
            this.companyDescription = companyDescription;
            return this;
        }

        public Builder setPersonality(Personality personality) {
            this.personality = personality;
            return this;
        }

        public Builder setVoice(String voice) {
            this.voice = voice;
            return this;
        }

        /**
         * Set custom system instructions. If set, this will override all other
         * personality and behavior settings.
         */
        public Builder setSystemInstructions(String systemInstructions) {
            this.systemInstructions = systemInstructions;
            return this;
        }

        public Builder setEagerToShowPackages(boolean eager) {
            this.eagerToShowPackages = eager;
            return this;
        }

        public Builder setCollectLeadsEarly(boolean collect) {
            this.collectLeadsEarly = collect;
            return this;
        }

        public Builder setUseUpselling(boolean upsell) {
            this.useUpselling = upsell;
            return this;
        }

        public Builder setTemperature(double temperature) {
            this.temperature = Math.max(0.0, Math.min(1.0, temperature));
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(this);
        }
    }
}
