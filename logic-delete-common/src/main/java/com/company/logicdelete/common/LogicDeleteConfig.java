package com.company.logicdelete.common;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class LogicDeleteConfig {
    private List<ControlledTable> tables = new ArrayList<>();
    private WhiteListConfig whitelist = new WhiteListConfig();
    private ScanConfig scan = new ScanConfig();
    public List<ControlledTable> getTables(){return tables;} public void setTables(List<ControlledTable> v){tables=v;}
    public WhiteListConfig getWhitelist(){return whitelist;} public void setWhitelist(WhiteListConfig v){whitelist=v;}
    public ScanConfig getScan(){return scan;} public void setScan(ScanConfig v){scan=v;}

    public static class ControlledTable {
        private String schema, table, deleteField="delete_flag", normalValue, deletedValue, owner, remark;
        public String getSchema(){return schema;} public void setSchema(String v){schema=v;} public String getTable(){return table;} public void setTable(String v){table=v;}
        public String getDeleteField(){return deleteField;} public void setDeleteField(String v){deleteField=v;} public String getNormalValue(){return normalValue;} public void setNormalValue(String v){normalValue=v;}
        public String getDeletedValue(){return deletedValue;} public void setDeletedValue(String v){deletedValue=v;} public String getOwner(){return owner;} public void setOwner(String v){owner=v;} public String getRemark(){return remark;} public void setRemark(String v){remark=v;}
        public String qualifiedName(){return schema==null||schema.trim().isEmpty()?normalize(table):normalize(schema)+"."+normalize(table);} public String shortName(){return normalize(table);}
        private static String normalize(String s){return s==null?"":s.replace("\"","").trim().toLowerCase();}
    }
    public static class WhiteListConfig {
        private List<WhiteListItem> physicalDelete=new ArrayList<>(), queryDeletedData=new ArrayList<>();
        public List<WhiteListItem> getPhysicalDelete(){return physicalDelete;} public void setPhysicalDelete(List<WhiteListItem> v){physicalDelete=v;}
        public List<WhiteListItem> getQueryDeletedData(){return queryDeletedData;} public void setQueryDeletedData(List<WhiteListItem> v){queryDeletedData=v;}
    }
    public static class WhiteListItem {
        private String sqlId,reason,owner; private LocalDate expireDate;
        public String getSqlId(){return sqlId;} public void setSqlId(String v){sqlId=v;} public String getReason(){return reason;} public void setReason(String v){reason=v;} public String getOwner(){return owner;} public void setOwner(String v){owner=v;} public LocalDate getExpireDate(){return expireDate;} public void setExpireDate(LocalDate v){expireDate=v;}
    }
    public static class ScanConfig {
        private List<String> mapperPattern=new ArrayList<>(), ignorePath=new ArrayList<>();
        public ScanConfig(){mapperPattern.add("**/*.opengauss.xml");ignorePath.add("**/target/**");ignorePath.add("**/build/**");ignorePath.add("**/.git/**");}
        public List<String> getMapperPattern(){return mapperPattern;} public void setMapperPattern(List<String> v){mapperPattern=v;} public List<String> getIgnorePath(){return ignorePath;} public void setIgnorePath(List<String> v){ignorePath=v;}
    }
}
