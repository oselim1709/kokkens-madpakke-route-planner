package dk.madpakke.domain;

import java.util.ArrayList;
import java.util.List;

/** A named, reusable snapshot of stops — save the usual delivery list once, apply it before each run. */
public class StopTemplate {

    private Long id;
    private String name;
    private String createdAt;
    private List<Stop> items = new ArrayList<>();
    private int itemCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public List<Stop> getItems() {
        return items;
    }

    public void setItems(List<Stop> items) {
        this.items = items;
    }

    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }
}
