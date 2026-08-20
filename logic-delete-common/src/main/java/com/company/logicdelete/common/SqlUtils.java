package com.company.logicdelete.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class SqlUtils {
    private SqlUtils() {}
    public static String maskCommentsAndLiterals(String sql) {
        StringBuilder out=new StringBuilder(); boolean single=false,line=false,block=false;
        for(int i=0;i<sql.length();i++){char c=sql.charAt(i), n=i+1<sql.length()?sql.charAt(i+1):0;
            if(line){if(c=='\n'){line=false;out.append(c);}else out.append(' ');continue;}
            if(block){if(c=='*'&&n=='/'){out.append("  ");i++;block=false;}else out.append(c=='\n'?'\n':' ');continue;}
            if(single){if(c=='\''&&n=='\''){out.append("  ");i++;}else if(c=='\''){single=false;out.append(' ');}else out.append(c=='\n'?'\n':' ');continue;}
            if(c=='-'&&n=='-'){out.append("  ");i++;line=true;}else if(c=='/'&&n=='*'){out.append("  ");i++;block=true;}else if(c=='\''){out.append(' ');single=true;}else out.append(c);
        } return out.toString();
    }
    public static String normalize(String sql){return maskCommentsAndLiterals(sql).replaceAll("#\\{[^}]+}","?").replaceAll("\\s+"," ").trim().toLowerCase();}
    public static String sha256(String value){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(normalize(value).getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
