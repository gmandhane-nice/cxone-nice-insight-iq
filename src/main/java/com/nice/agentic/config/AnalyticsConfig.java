package com.nice.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Externalized configuration for analytics thresholds and cost model parameters.
 *
 * <p>All values can be overridden via application.yaml under the prefix
 * {@code agentic.analytics}.</p>
 */
@Configuration
@ConfigurationProperties(prefix = "agentic.analytics")
public class AnalyticsConfig {

    // -------------------------------------------------------------------------
    // ROI Cost Model
    // -------------------------------------------------------------------------

    private Roi roi = new Roi();

    // -------------------------------------------------------------------------
    // Shrinkage Thresholds
    // -------------------------------------------------------------------------

    private Shrinkage shrinkage = new Shrinkage();

    // -------------------------------------------------------------------------
    // Burnout Risk Thresholds
    // -------------------------------------------------------------------------

    private Burnout burnout = new Burnout();

    // -------------------------------------------------------------------------
    // Demand Forecast Parameters
    // -------------------------------------------------------------------------

    private Forecast forecast = new Forecast();

    // -------------------------------------------------------------------------
    // Getters / Setters
    // -------------------------------------------------------------------------

    public Roi getRoi() {
        return roi;
    }

    public void setRoi(Roi roi) {
        this.roi = roi;
    }

    public Shrinkage getShrinkage() {
        return shrinkage;
    }

    public void setShrinkage(Shrinkage shrinkage) {
        this.shrinkage = shrinkage;
    }

    public Burnout getBurnout() {
        return burnout;
    }

    public void setBurnout(Burnout burnout) {
        this.burnout = burnout;
    }

    public Forecast getForecast() {
        return forecast;
    }

    public void setForecast(Forecast forecast) {
        this.forecast = forecast;
    }

    // =========================================================================
    // Nested configuration classes
    // =========================================================================

    public static class Roi {
        /** Loaded agent cost per hour in dollars. */
        private double costPerAgentHour = 25.0;

        /** Cost per contact in dollars. */
        private double costPerContact = 0.50;

        /** Cost to replace an agent lost to attrition in dollars. */
        private double attritionReplacementCost = 8000.0;

        /** Penalty per percentage point of SLA breach in dollars. */
        private double slaBreachPenaltyPerPct = 500.0;

        public double getCostPerAgentHour() {
            return costPerAgentHour;
        }

        public void setCostPerAgentHour(double costPerAgentHour) {
            this.costPerAgentHour = costPerAgentHour;
        }

        public double getCostPerContact() {
            return costPerContact;
        }

        public void setCostPerContact(double costPerContact) {
            this.costPerContact = costPerContact;
        }

        public double getAttritionReplacementCost() {
            return attritionReplacementCost;
        }

        public void setAttritionReplacementCost(double attritionReplacementCost) {
            this.attritionReplacementCost = attritionReplacementCost;
        }

        public double getSlaBreachPenaltyPerPct() {
            return slaBreachPenaltyPerPct;
        }

        public void setSlaBreachPenaltyPerPct(double slaBreachPenaltyPerPct) {
            this.slaBreachPenaltyPerPct = slaBreachPenaltyPerPct;
        }
    }

    public static class Shrinkage {
        /** Assumed shift duration in seconds (default 8 hours). */
        private int shiftSeconds = 28800;

        /** Loaded agent cost per hour in dollars. */
        private double costPerHour = 25.0;

        /** Normal shrinkage rate (breaks, training, meetings). */
        private double normalShrinkage = 0.30;

        /** Threshold above which shrinkage is considered elevated. */
        private double elevatedThreshold = 0.35;

        /** Threshold above which shrinkage is considered excessive. */
        private double excessiveThreshold = 0.45;

        /** Factor above team average ACW that triggers an "Extended ACW" flag. */
        private double acwFlagFactor = 1.5;

        public int getShiftSeconds() {
            return shiftSeconds;
        }

        public void setShiftSeconds(int shiftSeconds) {
            this.shiftSeconds = shiftSeconds;
        }

        public double getCostPerHour() {
            return costPerHour;
        }

        public void setCostPerHour(double costPerHour) {
            this.costPerHour = costPerHour;
        }

        public double getNormalShrinkage() {
            return normalShrinkage;
        }

        public void setNormalShrinkage(double normalShrinkage) {
            this.normalShrinkage = normalShrinkage;
        }

        public double getElevatedThreshold() {
            return elevatedThreshold;
        }

        public void setElevatedThreshold(double elevatedThreshold) {
            this.elevatedThreshold = elevatedThreshold;
        }

        public double getExcessiveThreshold() {
            return excessiveThreshold;
        }

        public void setExcessiveThreshold(double excessiveThreshold) {
            this.excessiveThreshold = excessiveThreshold;
        }

        public double getAcwFlagFactor() {
            return acwFlagFactor;
        }

        public void setAcwFlagFactor(double acwFlagFactor) {
            this.acwFlagFactor = acwFlagFactor;
        }
    }

    public static class Burnout {
        // AHT change thresholds (fraction)
        private double ahtHighThreshold = 0.20;
        private double ahtMediumThreshold = 0.10;
        private double ahtLowThreshold = 0.05;

        // AHT scoring points
        private int ahtHighPoints = 30;
        private int ahtMediumPoints = 20;
        private int ahtLowPoints = 10;

        // Refusal rate thresholds (fraction)
        private double refusalHighThreshold = 0.15;
        private double refusalMediumThreshold = 0.10;
        private double refusalLowThreshold = 0.05;

        // Refusal scoring points
        private int refusalHighPoints = 25;
        private int refusalMediumPoints = 20;
        private int refusalLowPoints = 10;

        // Volume drop thresholds (negative fraction)
        private double volumeHighThreshold = -0.30;
        private double volumeMediumThreshold = -0.20;
        private double volumeLowThreshold = -0.10;

        // Volume drop scoring points
        private int volumeHighPoints = 25;
        private int volumeMediumPoints = 15;
        private int volumeLowPoints = 10;

        // Consistency (coefficient of variation) thresholds
        private double consistencyHighThreshold = 0.4;
        private double consistencyMediumThreshold = 0.3;
        private double consistencyLowThreshold = 0.2;

        // Consistency scoring points
        private int consistencyHighPoints = 20;
        private int consistencyMediumPoints = 15;
        private int consistencyLowPoints = 10;

        // Risk level score boundaries
        private int highRiskThreshold = 60;
        private int mediumRiskThreshold = 35;

        // Minimum risk score to include in results
        private int minimumDisplayScore = 25;

        public double getAhtHighThreshold() {
            return ahtHighThreshold;
        }

        public void setAhtHighThreshold(double ahtHighThreshold) {
            this.ahtHighThreshold = ahtHighThreshold;
        }

        public double getAhtMediumThreshold() {
            return ahtMediumThreshold;
        }

        public void setAhtMediumThreshold(double ahtMediumThreshold) {
            this.ahtMediumThreshold = ahtMediumThreshold;
        }

        public double getAhtLowThreshold() {
            return ahtLowThreshold;
        }

        public void setAhtLowThreshold(double ahtLowThreshold) {
            this.ahtLowThreshold = ahtLowThreshold;
        }

        public int getAhtHighPoints() {
            return ahtHighPoints;
        }

        public void setAhtHighPoints(int ahtHighPoints) {
            this.ahtHighPoints = ahtHighPoints;
        }

        public int getAhtMediumPoints() {
            return ahtMediumPoints;
        }

        public void setAhtMediumPoints(int ahtMediumPoints) {
            this.ahtMediumPoints = ahtMediumPoints;
        }

        public int getAhtLowPoints() {
            return ahtLowPoints;
        }

        public void setAhtLowPoints(int ahtLowPoints) {
            this.ahtLowPoints = ahtLowPoints;
        }

        public double getRefusalHighThreshold() {
            return refusalHighThreshold;
        }

        public void setRefusalHighThreshold(double refusalHighThreshold) {
            this.refusalHighThreshold = refusalHighThreshold;
        }

        public double getRefusalMediumThreshold() {
            return refusalMediumThreshold;
        }

        public void setRefusalMediumThreshold(double refusalMediumThreshold) {
            this.refusalMediumThreshold = refusalMediumThreshold;
        }

        public double getRefusalLowThreshold() {
            return refusalLowThreshold;
        }

        public void setRefusalLowThreshold(double refusalLowThreshold) {
            this.refusalLowThreshold = refusalLowThreshold;
        }

        public int getRefusalHighPoints() {
            return refusalHighPoints;
        }

        public void setRefusalHighPoints(int refusalHighPoints) {
            this.refusalHighPoints = refusalHighPoints;
        }

        public int getRefusalMediumPoints() {
            return refusalMediumPoints;
        }

        public void setRefusalMediumPoints(int refusalMediumPoints) {
            this.refusalMediumPoints = refusalMediumPoints;
        }

        public int getRefusalLowPoints() {
            return refusalLowPoints;
        }

        public void setRefusalLowPoints(int refusalLowPoints) {
            this.refusalLowPoints = refusalLowPoints;
        }

        public double getVolumeHighThreshold() {
            return volumeHighThreshold;
        }

        public void setVolumeHighThreshold(double volumeHighThreshold) {
            this.volumeHighThreshold = volumeHighThreshold;
        }

        public double getVolumeMediumThreshold() {
            return volumeMediumThreshold;
        }

        public void setVolumeMediumThreshold(double volumeMediumThreshold) {
            this.volumeMediumThreshold = volumeMediumThreshold;
        }

        public double getVolumeLowThreshold() {
            return volumeLowThreshold;
        }

        public void setVolumeLowThreshold(double volumeLowThreshold) {
            this.volumeLowThreshold = volumeLowThreshold;
        }

        public int getVolumeHighPoints() {
            return volumeHighPoints;
        }

        public void setVolumeHighPoints(int volumeHighPoints) {
            this.volumeHighPoints = volumeHighPoints;
        }

        public int getVolumeMediumPoints() {
            return volumeMediumPoints;
        }

        public void setVolumeMediumPoints(int volumeMediumPoints) {
            this.volumeMediumPoints = volumeMediumPoints;
        }

        public int getVolumeLowPoints() {
            return volumeLowPoints;
        }

        public void setVolumeLowPoints(int volumeLowPoints) {
            this.volumeLowPoints = volumeLowPoints;
        }

        public double getConsistencyHighThreshold() {
            return consistencyHighThreshold;
        }

        public void setConsistencyHighThreshold(double consistencyHighThreshold) {
            this.consistencyHighThreshold = consistencyHighThreshold;
        }

        public double getConsistencyMediumThreshold() {
            return consistencyMediumThreshold;
        }

        public void setConsistencyMediumThreshold(double consistencyMediumThreshold) {
            this.consistencyMediumThreshold = consistencyMediumThreshold;
        }

        public double getConsistencyLowThreshold() {
            return consistencyLowThreshold;
        }

        public void setConsistencyLowThreshold(double consistencyLowThreshold) {
            this.consistencyLowThreshold = consistencyLowThreshold;
        }

        public int getConsistencyHighPoints() {
            return consistencyHighPoints;
        }

        public void setConsistencyHighPoints(int consistencyHighPoints) {
            this.consistencyHighPoints = consistencyHighPoints;
        }

        public int getConsistencyMediumPoints() {
            return consistencyMediumPoints;
        }

        public void setConsistencyMediumPoints(int consistencyMediumPoints) {
            this.consistencyMediumPoints = consistencyMediumPoints;
        }

        public int getConsistencyLowPoints() {
            return consistencyLowPoints;
        }

        public void setConsistencyLowPoints(int consistencyLowPoints) {
            this.consistencyLowPoints = consistencyLowPoints;
        }

        public int getHighRiskThreshold() {
            return highRiskThreshold;
        }

        public void setHighRiskThreshold(int highRiskThreshold) {
            this.highRiskThreshold = highRiskThreshold;
        }

        public int getMediumRiskThreshold() {
            return mediumRiskThreshold;
        }

        public void setMediumRiskThreshold(int mediumRiskThreshold) {
            this.mediumRiskThreshold = mediumRiskThreshold;
        }

        public int getMinimumDisplayScore() {
            return minimumDisplayScore;
        }

        public void setMinimumDisplayScore(int minimumDisplayScore) {
            this.minimumDisplayScore = minimumDisplayScore;
        }
    }

    public static class Forecast {
        /** Average contacts handled by one agent per hour. */
        private int contactsPerAgentPerHour = 8;

        /** Number of hours to forecast ahead. */
        private int forecastHours = 4;

        /** Number of historical days to use for pattern analysis. */
        private int historyDays = 28;

        public int getContactsPerAgentPerHour() {
            return contactsPerAgentPerHour;
        }

        public void setContactsPerAgentPerHour(int contactsPerAgentPerHour) {
            this.contactsPerAgentPerHour = contactsPerAgentPerHour;
        }

        public int getForecastHours() {
            return forecastHours;
        }

        public void setForecastHours(int forecastHours) {
            this.forecastHours = forecastHours;
        }

        public int getHistoryDays() {
            return historyDays;
        }

        public void setHistoryDays(int historyDays) {
            this.historyDays = historyDays;
        }
    }
}
