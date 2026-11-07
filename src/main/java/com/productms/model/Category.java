package com.productms.model;

/** Product category; a category with a parent is a subcategory. */
public class Category {
    public int categoryId;
    public String name;
    public Integer parentId;
    public String parentName;
    public String description;
    public boolean active = true;

    public String displayName() {
        return parentName == null ? name : parentName + " › " + name;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
