package com.example.backend.service.recruitmentteam;

import com.example.backend.dto.recruitmentteam.AcceptMemberRequestDTO;
import com.example.backend.dto.recruitmentteam.AcceptMemberResponseDTO;
import com.example.backend.model.entity.member.Member;
import com.example.backend.model.entity.notification.Notification;
import com.example.backend.model.entity.notification.NotificationMessage;
import com.example.backend.model.entity.recruitmentteam.AcceptMember;
import com.example.backend.model.entity.recruitmentteam.ApplicationStatus;
import com.example.backend.model.entity.recruitmentteam.Recruitment;
import com.example.backend.model.entity.recruitmentteam.TeamApplication;
import com.example.backend.model.repository.member.MemberRepository;
import com.example.backend.model.repository.recruitmentteam.AcceptMemberRepository;
import com.example.backend.model.repository.recruitmentteam.RecruitmentRepository;
import com.example.backend.model.repository.recruitmentteam.TeamApplicationRepository;
import com.example.backend.service.notification.FCM.FCMService;
import com.example.backend.service.notification.NotificationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AcceptMemberImplService implements AcceptMemberService{

    private final AcceptMemberRepository acceptMemberRepository;
    private final MemberRepository memberRepository;
    private final RecruitmentRepository recruitmentRepository;
    private final TeamApplicationRepository teamApplicationRepository;
    private final NotificationService notificationService;
    private final FCMService fcmService;

    @Override
    public AcceptMemberResponseDTO acceptMember(Authentication authentication, boolean accept,
                                                AcceptMemberRequestDTO acceptMemberRequestDTO) {
        String studentId = authentication.getName();
        Member member = memberRepository.findByStudentId(studentId);

        Recruitment recruitment = recruitmentRepository.findById(acceptMemberRequestDTO.getRecruitmentId())
                .orElseThrow(() -> new IllegalArgumentException("해당 모집글이 없습니다."));

        TeamApplication teamApplication = teamApplicationRepository.findByMemberIdAndRecruitmentId(acceptMemberRequestDTO.getMemberId(), acceptMemberRequestDTO.getRecruitmentId());

        Member acceptMember = memberRepository.findById(acceptMemberRequestDTO.getMemberId())
                .orElseThrow(() -> new IllegalArgumentException("해당 멤버가 없습니다."));

        if (!recruitment.getMember().getId().equals(member.getId())) {
            throw new IllegalArgumentException("해당 모집글에 대한 권한이 없습니다.");
        }

        AcceptMember saveAcceptMember = new AcceptMember();
        if (accept && recruitment.isRecruitmentStatus()) {
            saveAcceptMember = acceptMemberRepository.save(
                    acceptMemberRequestDTO.toEntity(acceptMember, recruitment));

            teamApplication.setApplicationStatus(ApplicationStatus.ACCEPTED);
            teamApplicationRepository.save(teamApplication);

            int count = recruitment.getAcceptMembers().size()+1;
            if (count == recruitment.getStudentCount()) {
                recruitment.setRecruitmentStatus(false);
                recruitmentRepository.save(recruitment);
            }

            NotificationMessage message = NotificationMessage.builder()
                    .id(UUID.randomUUID().toString())
                    .title("모집글 수락")
                    .content(recruitment.getRecruitmentTitle()+"팀에 가입되었습니다.")
                    .category("팀원모집")
                    .targetId(recruitment.getId())
                    .createdAt(LocalDateTime.now())
                    .isRead(false)
                    .build();

            notificationService.addMessageToStudent(message, acceptMember.getStudentId());
            fcmService.sendMessageToStudent(acceptMember, message);

            // 같은 카테고리에 있는 다른 모집 글의 모든 수락된 멤버의 지원글 삭제
            deleteAcceptedApplicationsFromOtherRecruitments(recruitment.getRecruitmentCategory(), acceptMember.getId());

            return AcceptMemberResponseDTO.toResponseDTO(saveAcceptMember, acceptMember.getMemberProfile());
        } else if (!accept) {
            teamApplication.setApplicationStatus(ApplicationStatus.REJECTED);
            teamApplicationRepository.save(teamApplication);

        } else {
            throw new IllegalArgumentException("모집글이 마감되었습니다.");
        }

        return null;
    }

    private void deleteAcceptedApplicationsFromOtherRecruitments(String category, Long memberId) {
        List<Recruitment> otherRecruitments = recruitmentRepository.findByRecruitmentCategory(category);
        for (Recruitment otherRecruitment : otherRecruitments) {
            // 현재 모집 글에서 수락된 지원서를 제외한 모든 지원서 가져오기
            List<TeamApplication> allApplications = teamApplicationRepository.findByRecruitmentIdAndMemberId(otherRecruitment.getId(), memberId);
            List<TeamApplication> acceptedApplications = teamApplicationRepository.findByRecruitmentIdAndMemberIdAndApplicationStatus(otherRecruitment.getId(), memberId, ApplicationStatus.ACCEPTED);

            // 수락된 지원서를 제외한 지원서들을 삭제
            allApplications.removeAll(acceptedApplications);
            teamApplicationRepository.deleteAll(allApplications);
        }
    }
}
