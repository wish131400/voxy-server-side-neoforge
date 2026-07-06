package dev.xantha.vss.common;


/**
 * 姣忕帺瀹跺彂閫佸甫瀹界殑浠ょ墝妗讹紙token bucket锛夐檺閫熷櫒銆? *
 * <p>杩欏潡閫昏緫鍘熷厛鍐呰仈鍦?{@code PlayerRequestState} 涓紝鐩存帴璋冪敤 {@link System#nanoTime()}锛? * 鍥犳鏃犳硶鍦ㄥ崟鍏冩祴璇曢噷鎺у埗鏃堕棿銆佷篃灏辨棤娉曢獙璇佽ˉ鍏?/ 绐佸彂 / 闄愰€熻涓恒€傞噸鏋勬椂鎶婂畠鎶芥垚鐙珛绫伙紝
 * 骞跺皢鏃堕挓鎶借薄涓?{@link NanoClock}锛氱敓浜т唬鐮佷紶 {@code System::nanoTime}锛屾祴璇曚紶鍙帶鐨勫亣鏃堕挓銆? *
 * <p>鏈被涓嶄緷璧栦换浣?Minecraft / fastutil 绫诲瀷锛屾槸涓€鍧楀彲鐙珛缂栬瘧銆佺嫭绔嬫祴璇曠殑绾€昏緫鍗曞厓銆? * 琛屼负涓庡師 {@code PlayerRequestState} 涓?{@code refill / canSend / recordSend /
 * primeSendCredit / effectiveLimit / sendBurstCap} 瀹屽叏涓€鑷淬€? */
public final class BandwidthLimiter {

    /** 绐佸彂涓婇檺 = 闄愰€?/ 璇ラ櫎鏁帮紝鐢ㄤ簬鍏佽鐭椂绐佸彂浣嗕笉鏃犻檺绱Н銆?*/
    private static final long BURST_DIVISOR = 4L;
    /** 灏忎簬璇ラ棿闅旓紙绾崇锛夌殑涓ゆ refill 涔嬮棿涓嶅仛琛ュ厖锛岄伩鍏嶉珮棰戞棤鎰忎箟璁＄畻銆?*/
    private static final long MIN_REFILL_INTERVAL_NANOS = 1_000_000L;
    /** 鍗曟 refill 鏈€澶氭寜 1 绉掕锛岄槻姝㈤暱鏃堕棿绌洪棽鍚庝竴娆¤ˉ鍏ヨ繃閲忎护鐗屻€?*/
    private static final long MAX_REFILL_WINDOW_NANOS = 1_000_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    /** primeSendCredit 棰勫厖鍊兼椂鐨勭‖涓婇檺锛岃繘鏈?/ 绉诲姩鐬棿涓嶄細涓€娆℃斁鍑鸿繃澶氭祦閲忋€?*/
    private static final long MAX_PRIMED_SEND_CREDIT_BYTES = 128L * 1024L;

    /** 绾崇鏃堕挓鎶借薄銆傜敓浜т紶 {@code System::nanoTime}锛屾祴璇曚紶鍙帶瀹炵幇銆?*/
    @FunctionalInterface
    public interface NanoClock {
        long nanoTime();
    }

    private final NanoClock clock;

    private long availableBytes;
    private long lastRefillNanos;
    private long totalBytesSent;
    private long desiredBandwidth = Long.MAX_VALUE;

    public BandwidthLimiter(NanoClock clock) {
        this.clock = clock;
        this.lastRefillNanos = clock.nanoTime();
    }

    /** 瀹㈡埛绔湡鏈涚殑涓嬭浇閫熺巼锛泏@code <= 0} 琛ㄧず涓嶉澶栭檺鍒讹紙浠呭彈鏈嶅姟绔笂闄愮害鏉燂級銆?*/
    public void setDesiredBandwidth(long desiredBandwidth) {
        this.desiredBandwidth = desiredBandwidth > 0L ? desiredBandwidth : Long.MAX_VALUE;
    }

    public long desiredBandwidth() {
        return desiredBandwidth;
    }

    public long totalBytesSent() {
        return totalBytesSent;
    }

    public long availableBytes() {
        return availableBytes;
    }

    /** 鍏堟寜缁忚繃鏃堕棿琛ュ厖浠ょ墝锛屽啀鍒ゆ柇鏄惁杩樻湁鍙彂閫侀搴︺€?*/
    public boolean canSend(long configuredLimit) {
        refill(configuredLimit);
        return availableBytes > 0L;
    }

    /** 璁板綍涓€娆″疄闄呭彂閫侊紝鎵ｅ噺鍙敤棰濆害锛堜笉涓鸿礋锛夊苟绱宸插彂瀛楄妭銆?*/
    public void recordSend(int bytes) {
        availableBytes = Math.max(0L, availableBytes - bytes);
        totalBytesSent += bytes;
    }

    /** 棰勫厖鍊煎彂閫侀搴︼紝鐢ㄤ簬杩涙湇 / 绉诲姩绛夐渶瑕佺珛鍗冲搷搴旂殑鍦烘櫙锛屽彈绐佸彂涓婇檺涓庣‖涓婇檺鍙岄噸绾︽潫銆?*/
    public void primeSendCredit(long configuredLimit) {
        refill(configuredLimit);
        long primedCredit = Math.min(sendBurstCap(effectiveLimit(configuredLimit)), MAX_PRIMED_SEND_CREDIT_BYTES);
        availableBytes = primedCredit;
        lastRefillNanos = clock.nanoTime();
    }

    private void refill(long configuredLimit) {
        long limit = effectiveLimit(configuredLimit);
        long now = clock.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos < MIN_REFILL_INTERVAL_NANOS) {
            return;
        }
        lastRefillNanos = now;
        elapsedNanos = Math.min(elapsedNanos, MAX_REFILL_WINDOW_NANOS);
        long refill = elapsedNanos * limit / NANOS_PER_SECOND;
        availableBytes = Math.min(availableBytes + refill, sendBurstCap(limit));
    }

    private long effectiveLimit(long configuredLimit) {
        long safeConfiguredLimit = Math.max(1L, configuredLimit);
        return Math.min(safeConfiguredLimit, desiredBandwidth);
    }

    private static long sendBurstCap(long limit) {
        return Math.max(1L, limit / BURST_DIVISOR);
    }
}
