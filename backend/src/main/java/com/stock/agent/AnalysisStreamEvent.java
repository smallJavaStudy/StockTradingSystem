package com.stock.agent;

import java.util.HashMap;
import java.util.Map;

public class AnalysisStreamEvent {
    private String direction;
    private String eventType;
    private String content;
    private Long timestamp;
    private Map<String, Object> meta;

    public AnalysisStreamEvent() {}

    public AnalysisStreamEvent(String direction, String eventType, String content, Long timestamp) {
        this.direction = direction;
        this.eventType = eventType;
        this.content = content;
        this.timestamp = timestamp;
        this.meta = new HashMap<>();
    }

    public AnalysisStreamEvent(String direction, String eventType, String content, Long timestamp,
                                Map<String, Object> meta) {
        this.direction = direction;
        this.eventType = eventType;
        this.content = content;
        this.timestamp = timestamp;
        this.meta = meta;
    }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) { this.meta = meta; }
}
