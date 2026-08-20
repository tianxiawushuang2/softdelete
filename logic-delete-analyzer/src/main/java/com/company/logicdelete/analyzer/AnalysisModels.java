package com.company.logicdelete.analyzer;

import java.nio.file.Path;
import java.util.*;

final class AnalysisModels {
    static final class Statement { Path file; String namespace,id,tag,sql; int line; boolean dynamic,dynamicInclude,includeSuccess=true; final List<String> expansionRisks=new ArrayList<>(); String fullId(){return namespace+"."+id;} }
    static final class Fragment { String namespace,id; org.w3c.dom.Element element; String fullId(){return namespace+"."+id;} }
    static final class Result {
        String sqlId,file,type,evidence; int line; Set<String> tables=new LinkedHashSet<>(),controlled=new LinkedHashSet<>(); boolean deleteFlag,physicalDelete,dynamic; List<Risk> risks=new ArrayList<>();
        String level(){String best="INFO";for(Risk r:risks)if(rank(r.level)>rank(best))best=r.level;return best;} String codes(){StringJoiner j=new StringJoiner("|");for(Risk r:risks)j.add(r.code);return j.toString();}
        private static int rank(String x){return "HIGH".equals(x)?4:"UNKNOWN".equals(x)?3:"MEDIUM".equals(x)?2:"LOW".equals(x)?1:0;}
    }
    static final class Risk { String level,code,message; Risk(String l,String c,String m){level=l;code=c;message=m;} }
}
