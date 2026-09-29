# AIF-C01 - Đọc nhanh trước giờ thi (10-15 phút)

> Bản rút gọn của `AIF-C01-NOTES.md`: chỉ giữ **mẹo, keyword, lỗi lặp lại**. Cần chi tiết => xem số mục trong ngoặc (vd "3.4").

---

## 1. Trước khi làm bài: 5 thói quen

1. **Đọc CUỐI câu trước**: requirement nằm ở cuối ("... provide a **concise overview**", "... **MOST cost-effective**")
2. **Xem INPUT là gì** (text / ảnh / audio / số). Service không nhận input đó => **gạch ngay** (text => loại Rekognition)
3. **Tìm "từ độc"** trong đáp án dài: "from **scratch**", "**never**", "**100%**", "**only**", "eliminating the need for validation"
4. **Đề có 2 yêu cầu?** Kiểm tra từng đáp án với **cả 2** (vd "classify 20 nhóm" + "explain inner mechanism" => decision tree)
5. **2 đáp án trông giống nhau** (K-means / k-NN, Confusion / Correlation matrix) => hỏi: mỗi cái **làm gì** và dùng **lúc nào**?

⚙️ **Luật thi**: 65 câu, 90 phút, đậu >= 700/1000. **Sai không bị trừ điểm => KHÔNG bỏ trống câu nào.** Có câu **ordering** (sắp xếp) và **matching** (nối cặp).

---

## 2. 🔁 Lỗi đã lặp lại (đọc kỹ nhất)

| # | Chủ đề | Quy tắc 1 dòng |
|---|---|---|
| 1 | **Unsupervised / no labels** (sai **7 lần**) | "**group / segment / similar**" => **K-means (unsupervised)**. "**no labels**" => **gạch hết** supervised (decision tree, linear / logistic regression, classification) => còn lại autoencoders / clustering |
| 2 | **Accuracy** (3 lần) | "correct / **total**", "**proportion classified correctly**" => **Accuracy**. Classification => **không phải** RMSE / MAE (regression). Precision luôn nói tới "**positive**" |
| 3 | **Overfitting** (2 lần) | "**good on training, bad on new data**" => **overfit** => **tăng** regularization. ❌ thêm epochs / features |
| 4 | **Inference** (2 lần) | "**trained model predicts new / unseen data**" => **inference** |
| 5 | **Hallucination** (2 lần) | output **sai / không liên quan / bịa** => **hallucination**. **RAG** để giảm. (Khác **nondeterminism** = mỗi lần ra **khác nhau**) |
| 6 | **Guardrails** (2 lần) | **Content filters** chỉ có 5 loại: **H-I-S-V-M** (Hate, Insults, Sexual, Violence, Misconduct). **Politics**, religion, gambling, bất kỳ **chủ đề** nào => **Denied topics** |
| 7 | **F1** | churn, fraud, **imbalanced** => **F1** (accuracy đánh lừa khi data lệch) |
| 8 | **Algorithm accountability** (2 lần) | AI **chấm điểm / quyết định về con người** (credit score, loan, hiring) => **algorithm accountability laws**. ❌ payment card laws (credit **score** ≠ credit **card**) |

💡 Mẹo nhớ H-I-S-V-M: "**H**ôm **I**t **S**ợ **V**ợ **M**ắng"

---

## 3. ⚠️ Đáp án mock đã kiểm tra là SAI (đừng học theo)

| Câu | Mock ghi | Đúng theo AWS |
|---|---|---|
| Guardrails content categories (Q6) | Politics + Gambling | chỉ **Violence** đúng (Hate, Insults, Sexual, Violence, Misconduct) |
| Comprehend endpoint **không dùng 15 ngày** (Q71) | CloudWatch | **Trusted Advisor** (có check "Comprehend Underutilized Endpoints", đúng **15 ngày**) |
| "Prompt strength" vs CFG scale (Q49) | 2 đáp án khác nhau | trên Bedrock console, **Prompt strength = cfg_scale**. Chọn tên "**CFG scale**" |

---

## 4. Các cặp dễ nhầm

**ML cơ bản (Domain 1)**

| Cặp | Phân biệt |
|---|---|
| **K-means** vs **k-NN** | K-**means** = K **nhóm**, **unsupervised**, **TẠO** nhóm. k-**NN** = K **hàng xóm**, **supervised**, xếp vào nhóm **có sẵn** |
| **Regression** vs **Classification** | "How much? / **số**" (giá, nhiệt độ) => Regression. "Which one? / **nhãn**" (spam, loài) => Classification |
| **Logistic** regression ⚠️ | tên có "regression" nhưng là **CLASSIFICATION** (xác suất có / không). Dự đoán **giá / số** => **Linear** regression |
| **Fraud** có nhãn vs không | nhãn fraud / not fraud => **Classification**. Không nhãn, tìm cái **lạ** => **Anomaly detection** |
| **Precision** vs **Recall** | Precision = sợ **báo nhầm** (spam filter). Recall = sợ **bỏ sót** (ung thư, gian lận) |
| **Confusion** vs **Correlation** matrix | **Con**fusion = chấm **model** classification, **sau** train. **Cor**relation = biến trong **data** liên quan nhau, **trước** train (EDA) |
| **Overfit** vs **Underfit** | Overfit = **học vẹt** (tốt train, kém test) => tăng regularization, bớt features, early stopping. Underfit = **chưa học** (kém cả train) => thêm epochs / features, model phức tạp hơn |
| **High variance** vs **High bias** | o**V**erfit = high **V**ariance. underfit = high **B**ias ("**B**asic" model) |
| **Epochs** | tăng epochs => tăng accuracy lúc train (Q23), nhưng **quá nhiều => overfit** (Q43) |
| **Hyperparameter** vs **parameter** | Hyperparameter đặt **trước** train (learning rate, batch size, epochs). Parameter / weights model **tự học** |
| **Learning rate** | **cao** => nhanh nhưng dễ vượt điểm tối ưu. **thấp** => chính xác nhưng chậm |
| **Transformer / CNN / RNN** | **T** = **T**ất cả cùng lúc + self-attention (LLM). **C** = **C**ửa sổ lọc (ảnh). **R** = **R**epeat từng bước (time series) |
| **Interpretable model** | "explain / inner mechanism / adjust weights" => **decision tree, linear / logistic regression**. ❌ neural network (hộp đen) |

**GenAI + FM (Domain 2, 3)**

| Cặp | Phân biệt |
|---|---|
| **Hallucination** vs **Nondeterminism** | **Sai / bịa** => hallucination. **Mỗi lần khác** => nondeterminism. **Determinism KHÔNG phải capability** của GenAI |
| **RAG** vs **Fine-tuning** | data **thay đổi thường xuyên**, **cite sources**, **no retraining** => RAG. **tone / style / persona** => fine-tuning. **Hiểu kém thuật ngữ chuyên ngành** => **domain adaptation** fine-tuning (RAG không dạy thuật ngữ) |
| **Fine-tuning** (đủ 3 chữ) | **further training** + **labeled data** + **specific task**. "from scratch" = pre-training. "unlabeled" = continued pre-training. "smaller / faster" = distillation |
| **Continued pre-training** | **unlabeled** domain data, thêm kiến thức ngành |
| **Distillation** | teacher => student: **nhỏ, nhanh, rẻ (tới 75%)**, kém chính xác một chút |
| **Zero / One / Few-shot** | 0 ví dụ / 1 ví dụ / **vài** ví dụ ("examples" số nhiều => few-shot) |
| **Chain-of-thought** | "**step by step**", "show your work" |
| **Prompt chaining** vs CoT vs Tree of thoughts | task chia thành **nhiều bước gửi LLM lần lượt**, output trước làm input sau => **prompt chaining**. CoT = suy luận từng bước **trong 1 câu trả lời**. Tree of thoughts = thử **nhiều nhánh** |
| **Directional stimulus** (hiếm) | **model nhỏ** tạo **gợi ý / từ khóa** riêng cho từng input, gắn vào prompt để **chỉ hướng** LLM lớn. Không train lại LLM. 💡 Directional = chỉ đường, stimulus = gợi ý |
| Prompt **best practices** | **specific + concise**, **experiment + iterate**, đưa đủ **context**. ❌ vague, ❌ "always max tokens", ❌ bỏ context |
| **ReAct** | suy luận + **gọi tool / API** (check inventory real-time) |
| **Top K** vs **Top P** | Top **K** = **K**ount (số từ, số nguyên). Top **P** = **P**ercent (0-1) |
| **Temperature** vs **CFG scale** | text: temperature **thấp** = ít random. ảnh: CFG **cao** = bám prompt, ít random |
| Tham số **không** đổi giá / latency | **Temperature, Top K, Top P**. Giá + tốc độ phụ thuộc **số token** và **model size** |
| **ROUGE / BLEU / BERTScore** | **R**OUGE = **R**út gọn (tóm tắt). **B**LEU = **B**i-lingual (dịch). **BERT**Score = so **nghĩa** (chữ khác, ý giống). Perplexity: **thấp = tốt** |
| Bedrock auto-eval accuracy | tóm tắt => **BERTScore**. Hỏi đáp => **NLP-F1**. Kiến thức chung => **RWK**. Phân loại => **Accuracy** |
| **Human eval** vs **LLM-as-a-judge** | friendliness / tone / **style employees prefer** => **human** + custom prompts. Chấm số lượng lớn không cần người => LLM-as-a-judge |
| **Robustness** vs **ROUGE** | "**resemble provided examples**" => ROUGE. Robustness = ổn định khi input **thay đổi nhỏ** |
| Teen **slang / creative spelling** ⚠️ | 2 nguồn khác nhau. "**against reference examples**" + đo **cách viết** => **BLEU** (chữ trùng chính xác). Đo **ý nghĩa** => BERTScore |
| **Knowledge Base** vs **Agent** | KB = **thủ thư** (trả lời từ tài liệu). Agent = **trợ lý** (gọi API, làm nhiều bước). Thêm ví dụ cho agent => **advanced prompts** |
| RAG **offline** vs **online** | offline: **content embeddings + search index**. online: query embedding, retrieval, generation |

**Pricing**

| Traffic | => |
|---|---|
| **unpredictable**, mới thử | **On-demand** |
| **steady / predictable** + custom model | **Provisioned Throughput** |
| **không cần ngay**, rẻ nhất | **Batch** (tới 50%) |

**Responsible AI + Security (Domain 4, 5)**

| Cặp | Phân biệt |
|---|---|
| **Bias** vs **Fairness** | Bias = **vấn đề**. Fairness = **mục tiêu** |
| **Explain** vs **Interpret** vs **Transparent** | Explain = **vì sao** (nhìn input / output). Interpret = **mở hộp**, hiểu **cách** chạy. Transparent = **công khai** model / data |
| **Veracity** vs **Robustness** | đúng **sự thật** / **ổn định** khi input lạ |
| **Model Cards** vs **AI Service Cards** | Model Cards = model **của BẠN**. AI Service Cards = service **của AWS** |
| **Shapley values** | giải thích **từng dự đoán** (mỗi feature đóng góp bao nhiêu). Accuracy / confusion matrix = hiệu năng **tổng**, không giải thích |
| Check bias **ít công nhất** | **Benchmark datasets** (có sẵn) |
| **Clarify** vs **Model Monitor** | Clarify = **bias, giải thích**. Model Monitor = **drift** trên production (fix: **retrain**) |
| **Clarify** vs **Guardrails** vs **A2I** | **đo** bias / **chặn** nội dung / **người** xem lại |
| **Sampling bias** | model flag 1 **nhóm sắc tộc** nhiều hơn => **sampling** bias => data augmentation |
| **CloudTrail / CloudWatch / Config** | Trail = **dấu chân** (ai làm gì). Watch = **theo dõi** (metrics, logs, alarms, performance). Config = **cấu hình** đổi ra sao |
| **Assess compliance** (chọn 2) | **Audit Manager + Config** |
| **Inspector** vs **Macie** | Inspector = **lỗ hổng** (EC2, ECR, Lambda). Macie = **PII trong S3** |
| **Artifact** vs **Audit Manager** | Artifact = tải báo cáo compliance **của AWS**. Audit Manager = bằng chứng **của BẠN** |
| **Trusted Advisor** | **đề xuất** 6 nhóm + phát hiện tài nguyên **không dùng** (underutilized) |
| **Poisoning / Injection / Jailbreak** | Poison = lúc **TRAIN**. Injection / hijacking = lúc **HỎI**. Jailbreak = **vượt rào** an toàn |
| Data bí mật đã lỡ train | **xóa model => bỏ data => train lại** (KMS / masking không xóa điều model đã học) |
| Không lộ PII khi fine-tune | **xóa PII TRƯỚC khi fine-tune** |
| Chống prompt injection | ít công nhất => **Guardrails**. Kỹ thuật prompt => **adversarial prompting** |
| Bedrock **safety** features (chọn 2) | **Guardrails** + **watermark detection** (nhận biết ảnh do Titan tạo). ❌ prompt management, KB, customization |

---

## 5. AI services: nhìn INPUT => OUTPUT

| Input => Output | Service |
|---|---|
| 🎤 giọng nói => chữ | **Transcribe** (tran**SCRIBE** = người chép) |
| 📝 chữ => giọng nói | **Polly** (con vẹt nói) |
| 📝 tiếng A => tiếng B | **Translate** |
| 📝 chữ => sentiment, entities, PII, **toxicity** | **Comprehend** |
| 📄 scan / PDF => chữ + form + bảng | **Textract** ("reads it". Comprehend "understands it") |
| 🖼️ ảnh / video => mặt, vật, **content moderation** | **Rekognition** (= **mắt**, không đọc được comment) |
| 💬 chatbot, **intent / slot / utterance**, virtual agent | **Lex** (dù có chữ "voice") |
| 🛒 lịch sử user => gợi ý | **Personalize** |
| ❓ câu hỏi => tài liệu (enterprise search) | **Kendra** (mock vẫn hỏi) |
| 📊 hỏi bằng lời => **charts / dashboards** | **Amazon Q in QuickSight** |
| 🧑 người kiểm tra kết quả model | **A2I**. Người **gắn nhãn** => **Ground Truth** (Plus = AWS lo nhân lực). Chợ thuê người => **Mechanical Turk** |

**Nội dung độc hại => chọn theo INPUT**: text => Comprehend toxicity · ảnh / video => Rekognition moderation · audio => Transcribe toxicity · prompt / response GenAI => Bedrock Guardrails

**Chip**: **Train**ium = **train**. **Infer**entia = **infer**ence. Cả 2: environmental footprint **thấp nhất**

---

## 6. Chọn service GenAI / SageMaker

| Đề nói | => |
|---|---|
| **no coding / no ML knowledge** | **SageMaker Canvas** (luôn luôn) |
| many FMs, 1 API, serverless, **select FMs** | **Bedrock** |
| pre-trained model hub | **SageMaker JumpStart** |
| **fully automated model tuning** | **SageMaker** (AMT / Autopilot) |
| share **variables (= features)** across teams | **Feature Store** |
| **only approved data** + ethical guidelines | **SageMaker Catalog** (mới) |
| CI/CD for ML | **Pipelines** |
| model **version** | **Model Registry** |
| chơi thử, **no AWS account** | **PartyRock** |
| multimodal **rẻ nhất** (Nova) | **Nova Lite** (Micro = chỉ text, Canvas = ảnh, Reel = video) |
| trả lời trong **N giây**, không train | **Model size** (nhỏ = nhanh) |
| xoay / lật ảnh | **Lambda** (không cần AI!) |
| **deterministic** / tính được chính xác | **không dùng ML**, viết code |

**Inference**: ms + traffic đều => **Real-time** · thất thường, chịu cold start => **Serverless** · tới **1 GB / 1 giờ**, queue => **Async** · cả dataset, không gấp => **Batch transform** · không internet => **Edge (SLM)**

---

## 7. Tên mới / đổi tên

| Cũ | Mới |
|---|---|
| Amazon Q **Business** | **Amazon Quick** |
| Amazon Q **Developer** (code) | **Kiro** (spec-driven IDE) |
| **CodeWhisperer** | đã thành **Q Developer** (2024) |
| **AWS Chatbot** | đổi tên thành **Q Developer** (Slack / Teams) |

**MCP** = ổ cắm **USB-C** (chuẩn nối agent với tool) · **Strands** = **bộ đồ nghề** (SDK viết agent) · **AgentCore** = **nhà xưởng** (chạy agent trên production: Runtime, Memory, Gateway, Identity, Policy) · **AWS Transform** = hiện đại hóa **code cũ**

---

## 8. Câu ORDERING: nhớ thứ tự

| Quy trình | Thứ tự |
|---|---|
| **ML project** | Business goal (KPI) => ML framing => Data (collect, **EDA**, preprocess) => Feature engineering => Train + tune => Evaluate => Deploy => Monitor (=> retrain). 💡 "**Goal - Frame - Data - Model - Deploy - Monitor**" |
| **FM lifecycle** (tạo FM) | data selection => model selection => pre-training => fine-tuning => evaluation => deployment => feedback |
| **Fine-tune trên Bedrock** | chọn **base model** => upload **labeled data S3** => chạy job => **evaluate** => Provisioned Throughput + deploy. 💡 "**Model - Data - Train - Test - Deploy**" |
| **Đánh giá bias** | định nghĩa fairness => kiểm tra data => bias metrics trên predictions => **Model Card** => monitor bias drift. 💡 "**Luật - Data - Model - Giấy tờ - Theo dõi**" |
| **Scoping Matrix** (ít => nhiều sở hữu) | 1 Consumer app => 2 Enterprise app => 3 Pre-trained => 4 Fine-tuned => 5 Self-trained |

---

## 9. Mẹo nhớ (tổng hợp)

- **AI ⊃ ML ⊃ DL ⊃ GenAI** (búp bê Nga). Agentic AI = GenAI + **tay chân** (tools)
- GenAI capabilities: "**Nhanh - Dễ - Linh hoạt - Sáng tạo**". Challenges: "**Luật - Xã hội - Dữ liệu - Sai sự thật - Hộp đen**". 2 từ bẫy ở cột Challenge: **Interpretability**, **Nondeterminism**
- 8 Responsible AI dimensions: **FEPT VGSC** (Fairness, Explainability, Privacy & security, Transparency, Veracity & robustness, Governance, Safety, Controllability)
- HCD "**stressful / high-pressure**" => **Amplified decision-making**
- Governance = **luật chơi** (policies, guidelines), không phải **mục tiêu** (doanh thu, tăng trưởng)
- Compliance = **bảo mật + bảo vệ data** (threat detection, data protection), không phải tốc độ / chi phí
- Đề hỏi hiệu quả **kinh doanh** => business metric (**task completion rate**, conversion rate, CSAT). Hỏi **kỹ thuật** => perplexity, BLEU, F1
- "**at runtime**" => inference latency (không phải training time)
- **MLOps** = **Machine Learning Operations** (Ops = Operations, giống DevOps)
- "**MOST operationally efficient**" + việc đơn giản => đáp án **đơn giản nhất**
