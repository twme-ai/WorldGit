import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.anvil.WorldLayout;

/** 僅修改驗收複本；未開作弊的實際 LevelSettings 由原版載入。 */
class LegacySingleWorld {
  public static void main(String[] args) throws Exception {
    Path file=Path.of(args[0]).resolve("level.dat");var root=WorldLayout.readGzip(file);var data=root.compound("Data");
    data.put("allowCommands",(byte)0);data.put("GameType",1);data.remove("Player");data.put("LevelName","WorldGitPhase5");
    data.put("SpawnX",8);data.put("SpawnY",65);data.put("SpawnZ",8);
    try(var stream=new GZIPOutputStream(Files.newOutputStream(file))){stream.write(Nbt.write(root));}
    System.out.println("legacy singleplayer fixture: cheats=false, creative, spawn=8,65,8");
  }
}
