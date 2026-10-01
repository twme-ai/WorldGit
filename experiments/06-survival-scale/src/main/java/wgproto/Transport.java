package wgproto;
import java.nio.file.*;
import java.util.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.*;

public final class Transport {
    public static void run(List<String> a) throws Exception {
        String op=a.get(0); long start=System.nanoTime();
        if(op.equals("clone")) {
            var cmd=Git.cloneRepository().setURI(a.get(1)).setDirectory(Path.of(a.get(2)).toFile()).setBare(true);
            if(a.size()>3) cmd.setDepth(Integer.parseInt(a.get(3)));
            try(var g=cmd.call()) {}
        } else try(var repo=new Repo(Path.of(a.get(1)),false)) {
            if(op.equals("push")) for(var result:Git.wrap(repo.repo).push().setRemote(a.get(2)).setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main")).call())
                for(var update:result.getRemoteUpdates()) {
                    System.out.println("PUSH " + update.getStatus());
                    if(update.getStatus()!=RemoteRefUpdate.Status.OK && update.getStatus()!=RemoteRefUpdate.Status.UP_TO_DATE) throw new IllegalStateException(update.toString());
                }
            else if(op.equals("fetch")) Git.wrap(repo.repo).fetch().setRemote(a.get(2)).setRefSpecs(new RefSpec("+refs/heads/main:refs/heads/main")).call();
            else throw new IllegalArgumentException(op);
        }
        System.out.println("TRANSPORT millis="+(System.nanoTime()-start)/1000000);
    }
}
