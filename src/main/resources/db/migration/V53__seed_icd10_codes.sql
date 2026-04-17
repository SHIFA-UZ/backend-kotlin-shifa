-- Seed minimal ICD-10 catalog for hybrid ICD-10 mapping.
-- Safe to re-run: ON CONFLICT DO NOTHING.

INSERT INTO icd10_codes(code, title, title_ru, keywords, parent_code)
VALUES
-- =========================
-- Respiratory (J00–J99)
-- =========================
('J00','Acute nasopharyngitis','Острый назофарингит','cold flu runny nose','J00'),
('J01.9','Acute sinusitis','Острый синусит','sinusitis facial pain','J01'),
('J02.9','Acute pharyngitis','Острый фарингит','sore throat infection','J02'),
('J03.9','Acute tonsillitis','Острый тонзиллит','tonsils infection','J03'),
('J04.0','Acute laryngitis','Острый ларингит','voice hoarseness','J04'),
('J06.9','Upper respiratory infection','ОРВИ','uri cold cough','J06'),
('J18.9','Pneumonia unspecified','Пневмония','pneumonia lung infection','J18'),
('J20','Acute bronchitis','Острый бронхит','bronchitis cough','J20'),
('J45.9','Asthma unspecified','Астма','asthma wheezing','J45'),
('J44.9','COPD unspecified','ХОБЛ','copd chronic lung','J44'),

-- =========================
-- Cardiovascular (I00–I99)
-- =========================
('I10','Hypertension','Гипертензия','high blood pressure','I10'),
('I11.9','Hypertensive heart disease','Гипертоническая болезнь сердца','hypertension heart','I11'),
('I20.9','Angina pectoris','Стенокардия','chest pain heart','I20'),
('I21.9','Myocardial infarction','Инфаркт миокарда','heart attack','I21'),
('I25.10','Coronary artery disease','ИБС','coronary disease','I25'),
('I48.0','Atrial fibrillation','Фибрилляция предсердий','afib arrhythmia','I48'),
('I50.9','Heart failure','Сердечная недостаточность','heart failure','I50'),

-- =========================
-- Endocrine (E00–E90)
-- =========================
('E10.9','Type 1 diabetes','СД 1 типа','diabetes insulin','E10'),
('E11.9','Type 2 diabetes','СД 2 типа','diabetes t2dm','E11'),
('E66.9','Obesity','Ожирение','obesity bmi','E66'),
('E03.9','Hypothyroidism','Гипотиреоз','low thyroid','E03'),
('E05.9','Hyperthyroidism','Гипертиреоз','thyroid high','E05'),

-- =========================
-- Gastrointestinal (K00–K93)
-- =========================
('K21.9','GERD','ГЭРБ','reflux heartburn','K21'),
('K29.7','Gastritis','Гастрит','stomach pain','K29'),
('K52.9','Gastroenteritis','Гастроэнтерит','diarrhea','K52'),
('K80.2','Gallstones','ЖКБ','gallstones','K80'),
('K35.9','Appendicitis','Аппендицит','appendix pain','K35'),

-- =========================
-- Dental (K00–K14)
-- =========================
('K00.0','Anodontia','Адонтия','missing teeth','K00'),
('K00.1','Supernumerary teeth','Сверхкомплектные зубы','extra teeth','K00'),
('K01.0','Impacted teeth','Ретинированные зубы','impacted wisdom tooth','K01'),
('K02.0','Caries enamel','Кариес эмали','early caries','K02'),
('K02.1','Caries dentine','Кариес дентина','tooth decay','K02'),
('K02.3','Arrested caries','Остановленный кариес','caries inactive','K02'),
('K03.0','Attrition teeth','Стирание зубов','tooth wear','K03'),
('K04.0','Pulpitis','Пульпит','tooth nerve pain','K04'),
('K04.1','Necrosis pulp','Некроз пульпы','dead tooth','K04'),
('K04.5','Apical periodontitis','Апикальный периодонтит','tooth abscess','K04'),
('K05.0','Gingivitis','Гингивит','gum inflammation','K05'),
('K05.1','Chronic gingivitis','Хронический гингивит','gum bleeding','K05'),
('K05.3','Periodontitis','Пародонтит','gum disease','K05'),
('K06.0','Gingival recession','Рецессия десны','gum recession','K06'),
('K07.6','TMJ disorders','ВНЧС нарушение','jaw pain','K07'),
('K08.1','Tooth loss','Потеря зуба','missing tooth','K08'),
('K08.8','Other tooth disorders','Другие болезни зубов','tooth problem','K08'),
('K09.0','Jaw cyst','Киста челюсти','jaw cyst','K09'),
('K10.2','Inflammatory jaw condition','Воспаление челюсти','jaw inflammation','K10'),
('K12.0','Aphthous stomatitis','Афтозный стоматит','mouth ulcers','K12'),
('K12.1','Other stomatitis','Стоматит','oral inflammation','K12'),
('K13.0','Lip disease','Болезни губ','lip disorder','K13'),
('K14.0','Glossitis','Глоссит','tongue inflammation','K14'),

-- =========================
-- Neurology (G00–G99)
-- =========================
('G43.9','Migraine','Мигрень','migraine headache','G43'),
('G44.2','Tension headache','ГБН','tension headache','G44'),
('G40.9','Epilepsy','Эпилепсия','seizure','G40'),

-- =========================
-- Musculoskeletal (M00–M99)
-- =========================
('M54.5','Low back pain','Боль в пояснице','back pain','M54'),
('M17.9','Knee osteoarthritis','Артроз колена','knee pain','M17'),
('M25.5','Joint pain','Боль в суставе','joint pain','M25'),

-- =========================
-- Infectious (A00–B99)
-- =========================
('A09','Infectious diarrhea','Инфекционная диарея','diarrhea infection','A09'),
('B34.9','Viral infection','Вирусная инфекция','virus','B34'),
('U07.1','COVID-19','COVID-19','covid','U07'),

-- =========================
-- Dermatology (L00–L99)
-- =========================
('L20.9','Atopic dermatitis','Атопический дерматит','eczema','L20'),
('L30.9','Dermatitis','Дерматит','rash','L30'),
('L70.0','Acne','Акне','pimples','L70'),

-- =========================
-- Mental (F00–F99)
-- =========================
('F41.1','Anxiety disorder','Тревожное расстройство','anxiety','F41'),
('F32.9','Depression','Депрессия','depression','F32'),
('F51.0','Insomnia','Бессонница','sleep problem','F51'),

-- =========================
-- Genitourinary (N00–N99)
-- =========================
('N39.0','UTI','Инфекция мочевых путей','uti','N39'),
('N20.0','Kidney stone','Камни почек','renal stone','N20'),

-- =========================
-- Symptoms (R00–R99)
-- =========================
('R50.9','Fever','Лихорадка','fever','R50'),
('R51','Headache','Головная боль','headache','R51'),
('R53','Fatigue','Слабость','fatigue','R53')
ON CONFLICT (code) DO NOTHING;