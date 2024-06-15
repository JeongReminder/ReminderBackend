package com.example.backend.service.recruitmentteam;

import com.example.backend.dto.recruitmentteam.RecruitmentRequestDTO;
import com.example.backend.dto.recruitmentteam.RecruitmentResponseDTO;
import org.springframework.security.core.Authentication;

public interface RecruitmentService {
    RecruitmentResponseDTO createRecruitment(Authentication authentication, RecruitmentRequestDTO recruitmentRequestDTO);
}
