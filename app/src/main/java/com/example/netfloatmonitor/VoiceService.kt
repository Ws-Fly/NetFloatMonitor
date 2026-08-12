// ===== 在 startSendThread 中减小缓冲区，降低延时 =====

private fun startSendThread() {
    sendThread?.interrupt()
    sendThread = thread(name = "VoiceSendThread") {
        val pcmBuffer = ByteArray(PCM_FRAME_SIZE)
        // ===== 减小批量发送，从 BATCH_COUNT=3 改为 2，降低延时 =====
        val compressedBatchBuf = ByteArray((PCM_FRAME_SIZE / 2) * 2)  // 2帧合并，延时约40ms
        var batchIndexLocal = 0
        var sendCount = 0

        Log.d(TAG, "📤 发送线程启动, 目标: ${multicastGroup?.hostAddress}:$multicastPort")

        while (isRunning.get() && isPilotMode.get() && !Thread.currentThread().isInterrupted) {
            try {
                val record = audioRecord
                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    Thread.sleep(50)
                    continue
                }

                if (isMuted.get()) {
                    Thread.sleep(10)
                    continue
                }

                val readSize = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (readSize > 0) {
                    val compressedFrame = encodeG711U(pcmBuffer, readSize)
                    
                    System.arraycopy(compressedFrame, 0, compressedBatchBuf, batchIndexLocal, compressedFrame.size)
                    batchIndexLocal += compressedFrame.size

                    if (batchIndexLocal >= compressedBatchBuf.size) {
                        val packet = DatagramPacket(
                            compressedBatchBuf,
                            compressedBatchBuf.size,
                            multicastGroup,
                            multicastPort
                        )
                        multicastSocket?.send(packet)
                        
                        batchIndexLocal = 0
                        txPackets.incrementAndGet()
                        sendCount++
                        
                        if (sendCount % 20 == 0) {
                            Log.d(TAG, "📤 已发送 $sendCount 批")
                        }
                    }
                }

            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isRunning.get() && isPilotMode.get()) {
                    Log.e(TAG, "发送异常: ${e.message}")
                }
            }
        }
        Log.d(TAG, "📤 发送线程已退出")
    }
}
