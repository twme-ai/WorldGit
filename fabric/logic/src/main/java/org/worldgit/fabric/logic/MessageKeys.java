package org.worldgit.fabric.logic;

import java.util.*;

/**
 * Fabric 模組用到的所有語言鍵與它們的參數。語言檔（i18n 模組的 en_us／zh_tw）必須有這些鍵，且樣板裡的
 * {@code <name>} 標籤必須是這裡宣告的參數（測試會逐一檢查），所以翻譯者不會因為漏鍵或寫錯參數而在遊戲裡
 * 看到鍵名或壞掉的標籤。
 */
public final class MessageKeys {
  private MessageKeys() {}

  private static final Map<String, Set<String>> REGISTRY = new TreeMap<>();

  private static String def(String key, String... args) {
    REGISTRY.put(key, Set.of(args));
    return key;
  }

  /** 全部鍵與其參數名稱。 */
  public static Map<String, Set<String>> all() {
    return Collections.unmodifiableMap(REGISTRY);
  }

  static void validate(String key, Set<String> given) {
    var declared = REGISTRY.get(key);
    if (declared == null) throw new IllegalArgumentException("未註冊的語言鍵：" + key);
    if (!declared.equals(given))
      throw new IllegalArgumentException("語言鍵 " + key + " 的參數應為 " + declared + "，實際為 " + given);
  }

  // i18n 模組的共用鍵
  public static final String COMMON_PREFIX = def("common.prefix");
  public static final String COMMON_NO_PERMISSION = def("common.no-permission");
  public static final String COMMON_ERROR = def("common.error", "message");
  public static final String COMMON_AXIOM_LOCKED = def("common.axiom.locked");

  public static final String MERGE_SELECT_UNSUPPORTED = def("fabric.merge.select-unsupported");

  // 說明
  public static final String HELP_TITLE = def("fabric.help.title");
  public static final String HELP_INIT = def("fabric.help.init");
  public static final String HELP_STATUS = def("fabric.help.status");
  public static final String HELP_COMMIT = def("fabric.help.commit");
  public static final String HELP_LOG = def("fabric.help.log");
  public static final String HELP_DIFF = def("fabric.help.diff");
  public static final String HELP_CLEAR = def("fabric.help.clear");
  public static final String HELP_INFO = def("fabric.help.info");
  public static final String HELP_RELOAD = def("fabric.help.reload");

  // status / diff
  public static final String STATUS_CLEAN = def("fabric.status.clean");
  public static final String STATUS_DIRTY = def("fabric.status.dirty");
  public static final String STATUS_DIMENSION =
      def("fabric.status.dimension", "dimension", "chunks", "sections", "added", "removed", "modified", "conflict");
  public static final String STATUS_EXTRA = def("fabric.status.extra", "entities", "biomes", "metadata");
  public static final String STATUS_SECTION =
      def("fabric.status.section", "x", "z", "y", "added", "removed", "modified", "conflict");
  public static final String STATUS_MORE = def("fabric.status.more", "count");
  public static final String STATUS_DIMENSION_FAILED = def("fabric.status.dimension-failed", "dimension", "message");
  public static final String WARNING = def("fabric.warning", "text");
  public static final String BLOCK_ADDED = def("fabric.diff.block.added", "x", "y", "z", "before", "after");
  public static final String BLOCK_REMOVED = def("fabric.diff.block.removed", "x", "y", "z", "before", "after");
  public static final String BLOCK_MODIFIED = def("fabric.diff.block.modified", "x", "y", "z", "before", "after");
  public static final String BLOCK_CONFLICT = def("fabric.diff.block.conflict", "x", "y", "z", "before", "after");
  public static final String BLOCK_MORE = def("fabric.diff.block-more");
  public static final String ENTITY_ADDED = def("fabric.diff.entity.added", "type", "uuid");
  public static final String ENTITY_REMOVED = def("fabric.diff.entity.removed", "type", "uuid");
  public static final String ENTITY_MODIFIED = def("fabric.diff.entity.modified", "type", "uuid");
  public static final String ENTITY_CONFLICT = def("fabric.diff.entity.conflict", "type", "uuid");
  public static final String RANGE_HEAD = def("fabric.diff.range-head");
  public static final String RANGE_ONE = def("fabric.diff.range-one", "from");
  public static final String RANGE_TWO = def("fabric.diff.range-two", "from", "to");
  public static final String DIFF_TOO_LARGE = def("fabric.diff.too-large", "dimension");

  // init / commit
  public static final String INIT_PROGRESS = def("fabric.init.progress");
  public static final String COMMIT_DONE_INIT = def("fabric.commit.done-init", "count", "snapshot");
  public static final String COMMIT_DONE = def("fabric.commit.done", "count", "snapshot");
  public static final String COMMIT_NOTHING = def("fabric.commit.nothing");
  public static final String COMMIT_INCOMPLETE = def("fabric.commit.incomplete", "ok", "failed");
  public static final String COMMIT_DIM_CHANGED =
      def("fabric.commit.dim.changed", "dimension", "commit", "added", "removed", "modified", "conflict");
  public static final String COMMIT_DIM_UNCHANGED = def("fabric.commit.dim.unchanged", "dimension");
  public static final String COMMIT_DIM_SKIPPED = def("fabric.commit.dim.skipped", "dimension");
  public static final String COMMIT_DIM_FAILED = def("fabric.commit.dim.failed", "dimension", "message");

  // log
  public static final String LOG_EMPTY = def("fabric.log.empty");
  public static final String LOG_HEADER = def("fabric.log.header");
  public static final String LOG_ROW = def("fabric.log.row", "snapshot", "message", "author", "time");
  public static final String LOG_ROW_AUTO = def("fabric.log.row-auto", "snapshot", "message", "author", "time");
  public static final String LOG_DIMENSIONS = def("fabric.log.dimensions", "dimensions");

  // preview
  public static final String PREVIEW_EMPTY = def("fabric.preview.empty");
  public static final String PREVIEW_GHOST = def("fabric.preview.ghost", "cells");
  public static final String PREVIEW_OUTLINE = def("fabric.preview.outline", "count");
  public static final String PREVIEW_CLEARED = def("fabric.preview.cleared");
  public static final String PREVIEW_NO_MOD = def("fabric.preview.no-mod", "state", "reason");
  public static final String PREVIEW_PLAYER_ONLY = def("fabric.preview.player-only");

  // info / reload
  public static final String INFO_TITLE = def("fabric.info.title", "version");
  public static final String INFO_WORLD = def("fabric.info.world", "path");
  public static final String INFO_REPO = def("fabric.info.repo", "path");
  public static final String INFO_HANDSHAKE = def("fabric.info.handshake", "state", "reason");
  public static final String INFO_TRACKED = def("fabric.info.tracked", "dimensions");
  public static final String INFO_UNTRACKED = def("fabric.info.untracked");
  public static final String RELOAD_DONE = def("fabric.reload.done");

  // 錯誤
  public static final String ERROR_UNKNOWN_OPTION = def("fabric.error.unknown-option", "option");
  public static final String ERROR_OPTION_NEEDS_VALUE = def("fabric.error.option-needs-value", "option");
  public static final String ERROR_OPTION_NO_VALUE = def("fabric.error.option-no-value", "option");
  public static final String ERROR_TEMPLATE = def("fabric.error.template");
  public static final String ERROR_EMPTY_MESSAGE = def("fabric.error.empty-message");
  public static final String ERROR_DIFF_ARGS = def("fabric.error.diff-args");
  public static final String ERROR_NOT_INITIALIZED = def("fabric.error.not-initialized");
  public static final String ERROR_NOT_STARTED = def("fabric.error.not-started");
  public static final String ERROR_INVALID_DIMENSION = def("fabric.error.invalid-dimension", "message");

  // 寫進 git 歷史的訊息（純文字；<player> 以文字替換）
  public static final String COMMIT_INIT = def("fabric.commit-message.init");
  public static final String COMMIT_AUTO_INTERVAL = def("fabric.commit-message.auto-interval");
  public static final String COMMIT_AUTO_LOGOUT = def("fabric.commit-message.auto-logout", "player");
  public static final String COMMIT_AUTO_STOP = def("fabric.commit-message.auto-stop");

  // 客戶端（/wgc 與連線提示）
  public static final String CLIENT_PALETTE = def("fabric.client.palette", "palette");
  public static final String CLIENT_PALETTE_INVALID = def("fabric.client.palette-invalid");
  public static final String CLIENT_SEE_THROUGH = def("fabric.client.see-through", "state");
  public static final String CLIENT_RELOADED = def("fabric.client.reloaded");
  public static final String CLIENT_RELOAD_FAILED = def("fabric.client.reload-failed", "message");
  public static final String CLIENT_CLEARED = def("fabric.client.cleared");
  public static final String CLIENT_STATUS_TITLE = def("fabric.client.status.title");
  public static final String CLIENT_STATUS_CONNECTION = def("fabric.client.status.connection", "state");
  public static final String CLIENT_STATUS_PREVIEW = def("fabric.client.status.preview", "dimension", "sections", "cells", "detail");
  public static final String CLIENT_STATUS_NO_PREVIEW = def("fabric.client.status.no-preview");
  public static final String CLIENT_STATUS_SETTINGS = def("fabric.client.status.settings", "palette", "seethrough");
  public static final String CLIENT_ON = def("fabric.client.value.on");
  public static final String CLIENT_OFF = def("fabric.client.value.off");
  public static final String CLIENT_CONNECTED = def("fabric.client.value.connected");
  public static final String CLIENT_WAITING = def("fabric.client.value.waiting");
  public static final String CLIENT_PALETTE_AUTO = def("fabric.client.value.palette-auto");
  public static final String CLIENT_PALETTE_DEFAULT = def("fabric.client.value.palette-default");
  public static final String CLIENT_PALETTE_COLORBLIND = def("fabric.client.value.palette-colorblind");

  public static final String HELP_PHASE2=def("fabric.help.phase2");
  public static final String APPLY_PROGRESS=def("fabric.apply.progress","phase","done","total");
  public static final String APPLY_RESULT=def("fabric.apply.result","state","sections","entities");
  public static final String APPLY_CANCEL=def("fabric.apply.cancel");
  public static final String APPLY_IDLE=def("fabric.apply.idle");
  public static final String BRANCH_ROW=def("fabric.branch.row","current","name","commits");
  public static final String BRANCH_DONE=def("fabric.branch.done","name");
  public static final String STASH_ROW=def("fabric.stash.row","index","id","message","time");
  public static final String STASH_EMPTY=def("fabric.stash.empty");
  public static final String STASH_DROPPED=def("fabric.stash.dropped","index");
  public static final String ERROR_PHASE2_ARGS=def("fabric.error.phase2-args","usage");
  public static final String APPLY_FINISHED=def("fabric.apply.finished","target");
  public static final String ERROR_DIRTY=def("fabric.error.dirty");
  public static final String ERROR_PARTIAL=def("fabric.error.partial");
  public static final String ERROR_VERIFY_FAILED=def("fabric.error.verify-failed","dimension","total","list","more");
  public static final String ERROR_WORLD_CHANGED=def("fabric.error.world-changed","retries","total","list","more");
  private static final Map<org.worldgit.platform.ApplyProgress.Phase,String> PHASES=new EnumMap<>(org.worldgit.platform.ApplyProgress.Phase.class);
  static { for(var phase:org.worldgit.platform.ApplyProgress.Phase.values()) PHASES.put(phase,def("fabric.apply.phase."+phase.name().toLowerCase(Locale.ROOT))); }
  public static String phase(org.worldgit.platform.ApplyProgress.Phase phase) { return PHASES.get(phase); }
  public static final String APPLY_DRY_RUN=def("fabric.apply.dry-run");
  public static final String HELP_PHASE3 = def("fabric.help.phase3");
  public static final String MERGE_STATUS = def("fabric.merge.status","remaining","total");
  public static final String MERGE_RESULT = def("fabric.merge.result","state","remaining");
  public static final String CONFLICT_EMPTY = def("fabric.merge.empty");
  public static final String CONFLICT_ROW = def("fabric.merge.row","id","dimension","bounds","count","ours","theirs","choice","resolved","redstone");

  public static final String REMOTE_USAGE=def("fabric.remote.usage");
  public static final String REMOTE_BUSY=def("fabric.remote.busy");
  public static final String REMOTE_START=def("fabric.remote.start");
  public static final String REMOTE_EMPTY=def("fabric.remote.empty");
  public static final String REMOTE_REMOTE_ROW=def("fabric.remote.remote-row","name","url");
  public static final String REMOTE_CONFIGURED=def("fabric.remote.configured");
  public static final String REMOTE_TRANSFERRED=def("fabric.remote.transferred","dimensions","mode");
  public static final String REMOTE_NON_FF=def("fabric.remote.non-ff");
  public static final String REMOTE_PREVIEW=def("fabric.remote.preview","chunks","mode","regions","seconds");
  public static final String REMOTE_CONFIRM=def("fabric.remote.confirm","code");
  public static final String REMOTE_EXPIRED=def("fabric.remote.expired");
  public static final String REMOTE_PREVIEW_CHANGED=def("fabric.remote.preview-changed");
  public static final String REMOTE_PR_ROW=def("fabric.remote.pr-row","number","source","state","target","title");
  public static final String REMOTE_PR_MORE=def("fabric.remote.pr-more","count");
  public static final String REMOTE_PR_DETAIL=def("fabric.remote.pr-detail","approvals","required","state");
  public static final String REMOTE_COMMENTED=def("fabric.remote.commented");
  public static final String REMOTE_COMMENT_ROW=def("fabric.remote.comment-row","dimension","text","x","y","z");
  public static final String REMOTE_HIDDEN=def("fabric.remote.hidden");
  public static final String REMOTE_SHOWN=def("fabric.remote.shown","count");
  public static final String REMOTE_NEW_VERSION=def("fabric.remote.new-version","branch");
  public static final String REMOTE_APPLIED=def("fabric.remote.applied","branch");
  public static final String REMOTE_NOTIFICATION_FAILED=def("fabric.remote.notification-failed");
  public static final String REMOTE_ERROR_UNAUTHORIZED=def("fabric.remote.error.unauthorized");
  public static final String REMOTE_ERROR_FORBIDDEN=def("fabric.remote.error.forbidden");
  public static final String REMOTE_ERROR_NOT_FOUND=def("fabric.remote.error.not_found");
  public static final String REMOTE_ERROR_CONFLICT=def("fabric.remote.error.conflict");
  public static final String REMOTE_ERROR_RATE_LIMIT=def("fabric.remote.error.rate_limit");
  public static final String REMOTE_ERROR_TIMEOUT=def("fabric.remote.error.timeout");
  public static final String REMOTE_ERROR_UNREACHABLE=def("fabric.remote.error.unreachable");
  public static final String REMOTE_ERROR_INVALID=def("fabric.remote.error.invalid");
  public static final String REMOTE_ERROR_TOO_LARGE=def("fabric.remote.error.too_large");
  public static final String REMOTE_ERROR_SERVER=def("fabric.remote.error.server");
  public static final String REMOTE_RESULT=def("fabric.remote.result","count","state");
  public static final String REMOTE_PERMISSION=def("fabric.remote.permission");
  public static final String REMOTE_NO_COMMENTS_MOD=def("fabric.remote.no-comments-mod");
  public static final String REMOTE_DIRTY=def("fabric.remote.dirty");
  public static final String REMOTE_MERGING=def("fabric.remote.merging");
  public static final String REMOTE_PARTIAL=def("fabric.remote.partial");

  // ---- Phase 5 任務 4 ----
  public static final String ERROR_TARGET_EXCLUSIVE = def("fabric.error.target-exclusive", "option");
  public static final String ERROR_CONSOLE_INIT = def("fabric.error.console-init");
  public static final String ERROR_DIMENSION_MISSING = def("fabric.error.dimension-missing", "dimension");
  public static final String ERROR_PERMISSION = def("fabric.error.permission");

  public static final String INIT_EXTRA_NETHER = def("fabric.init.extra-nether");
  public static final String INIT_EXTRA_END = def("fabric.init.extra-end");
  public static final String INIT_EXTRA_ALL = def("fabric.init.extra-all");
  public static final String INIT_EXTRA_HOVER = def("fabric.init.extra-hover", "command");

  public static final String RESULT_LINE = def("fabric.result.line", "operation", "status", "dimension", "summary", "millis");
  public static final String RESULT_COPY = def("fabric.result.copy");
  public static final String RESULT_COPY_HOVER = def("fabric.result.copy-hover");
  public static final String RESULT_RECOVERY = def("fabric.result.recovery");
  public static final String PROGRESS_LINE = def("fabric.progress.line", "operation", "dimension", "phase", "percent", "eta");
  public static final String PROGRESS_TERMINAL = def("fabric.progress.terminal", "operation", "status", "dimension", "millis");
  public static final String APPLY_PARTIAL = def("fabric.apply.partial", "sections", "entities");
  public static final String HELP_PHASE5 = def("fabric.help.phase5");
  public static final String HELP_TARGET = def("fabric.help.target");

  public static final List<String> STATUSES = List.of("success", "no_op", "partial", "failed", "cancelled");
  private static final Map<String, String> STATUS_KEYS = new TreeMap<>();
  static { for (String status : STATUSES) STATUS_KEYS.put(status, def("fabric.result.status." + status)); }
  public static String status(String status) { return STATUS_KEYS.get(status.toLowerCase(Locale.ROOT)); }

  /** 進度階段名稱（core 的 phase 識別字）；沒有翻譯的階段原樣顯示。 */
  public static final List<String> PROGRESS_PHASES = List.of("start", "scan", "scan-terrain", "scan-entities", "capture", "normalize", "diff", "preflight",
      "apply", "apply-terrain", "verify", "merge-compute", "merge-write", "merge", "merge-preview", "publish", "fetch", "push", "index", "lock",
      "lighting", "saving", "flush", "complete");
  private static final Map<String, String> PHASE_KEYS = new TreeMap<>();
  static { for (String phase : PROGRESS_PHASES) PHASE_KEYS.put(phase, def("fabric.phase." + phase)); }
  public static String phaseKey(String phase) { return PHASE_KEYS.get(phase); }

  public static final String GRAPH_TITLE = def("fabric.graph.title", "dimension", "page");
  public static final String GRAPH_MORE = def("fabric.graph.more", "page");
  public static final String GRAPH_EMPTY = def("fabric.graph.empty", "dimension");
  public static final String GRAPH_SCREEN = def("fabric.graph.screen", "dimension");
  public static final String GRAPH_NO_SCREEN = def("fabric.graph.no-screen");
  public static final String GRAPH_HOVER_SUFFIX = def("fabric.graph.hover", "id", "author", "time", "message", "parents");

  public static final String IGNORE_WRITTEN = def("fabric.ignore.written");
  public static final String IGNORE_VALID = def("fabric.ignore.valid", "count");
  public static final String IGNORE_TEST = def("fabric.ignore.test", "excluded", "line", "rule");
  public static final String IGNORE_PREVIEW = def("fabric.ignore.preview", "blocks", "bes", "entities", "biomes", "fields", "samples");
  public static final String IGNORE_CONFIRM = def("fabric.ignore.confirm", "code");
  public static final String IGNORE_ROW = def("fabric.ignore.row", "number", "state", "text");
  public static final String IGNORE_STALE = def("fabric.ignore.stale");
  public static final String IGNORE_MERGING = def("fabric.ignore.merging");
  public static final String IGNORE_NO_PENDING = def("fabric.ignore.no-pending");
  public static final String IGNORE_USAGE = def("fabric.ignore.usage");
  public static final String IGNORE_NO_TARGET = def("fabric.ignore.no-target");
  public static final String IGNORE_CANCELLED = def("fabric.ignore.cancelled");
  public static final String IGNORE_PERMISSION = def("fabric.ignore.permission");
  public static final String IGNORE_INVALID = def("fabric.ignore.invalid", "message");
  public static final String IGNORE_NO_SCREEN = def("fabric.ignore.no-screen");

  public static final String TAG_ROW = def("fabric.tag.row", "name", "commit");
  public static final String TAG_EMPTY = def("fabric.tag.empty");
  public static final String TAG_DONE = def("fabric.tag.done", "action", "name");
  public static final String TAG_CREATED = def("fabric.tag.created");
  public static final String TAG_DELETED = def("fabric.tag.deleted");
  public static final String VERIFY_DONE = def("fabric.verify.done", "revision");
  public static final String TOUCH_DUPLICATE = def("fabric.touched.duplicate", "uuid", "dimension");

  private static final Set<String> ERRORS = Set.of(COMMON_ERROR, ERROR_DIRTY, ERROR_PARTIAL, STATUS_DIMENSION_FAILED, COMMIT_DIM_FAILED, COMMIT_INCOMPLETE,
      APPLY_PARTIAL, REMOTE_PERMISSION, REMOTE_NON_FF, REMOTE_EXPIRED, REMOTE_PREVIEW_CHANGED, REMOTE_NOTIFICATION_FAILED, REMOTE_DIRTY,
      REMOTE_MERGING, REMOTE_PARTIAL, REMOTE_NO_COMMENTS_MOD, REMOTE_BUSY, REMOTE_USAGE, ERROR_PHASE2_ARGS, IGNORE_STALE, IGNORE_MERGING, IGNORE_NO_PENDING, IGNORE_USAGE,
      IGNORE_NO_TARGET, IGNORE_PERMISSION, IGNORE_INVALID, PREVIEW_NO_MOD, PREVIEW_PLAYER_ONLY, TOUCH_DUPLICATE);

  /** error／failure／partial 類的訊息：送出時附獨立 [複製]。 */
  public static boolean isError(String key) {
    return ERRORS.contains(key) || key.startsWith("fabric.error.") || key.startsWith("fabric.remote.error.");
  }
}
