package com.company.logicdelete.analyzer;

import com.company.logicdelete.common.LogicDeleteConfig.ScanConfig;
import java.io.IOException;import java.nio.file.*;import java.util.*;import java.util.stream.Stream;

final class MapperScanner {
    List<Path> scan(Path root, ScanConfig config) throws IOException { if(!Files.isDirectory(root))throw new IllegalArgumentException("Project directory does not exist: "+root); List<Path> out=new ArrayList<>();try(Stream<Path>s=Files.walk(root)){s.filter(Files::isRegularFile).filter(p->p.getFileName().toString().endsWith(".opengauss.xml")).filter(p->{String x=root.relativize(p).toString().replace('\\','/');return !x.contains("/target/")&&!x.contains("/build/")&&!x.startsWith(".git/");}).forEach(out::add);}Collections.sort(out);return out; }
}
